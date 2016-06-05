
package org.connectbot.transport;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.connectbot.R;
import org.connectbot.bean.HostBean;
import org.connectbot.service.TerminalBridge;
import org.connectbot.service.TerminalManager;
import org.connectbot.util.HostDatabase;

import com.felhr.deviceids.CH34xIds;
import com.felhr.deviceids.CP210xIds;
import com.felhr.deviceids.FTDISioIds;
import com.felhr.deviceids.PL2303Ids;
import com.felhr.usbserial.UsbSerialDevice;
import com.felhr.usbserial.UsbSerialInterface;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.util.Log;

public class USBSerial extends AbsTransport implements UsbSerialInterface.UsbReadCallback {
	private static final String TAG = "CB.USBSerial";
	private static final String PROTOCOL = "serial";
	private static final int DEFAULT_PORT = 0;

	private static final String ACTION_USB_PERMISSION = "org.connectbot.action.USB_PERMISSION";

	static final Pattern baudmask;
	static {
		baudmask = Pattern.compile("^(([^@]+)@)?(\\d+([noems][5-8]?)?r?)(:(\\d+))?$", Pattern.CASE_INSENSITIVE);
	}

	private UsbManager usbManager;
	private UsbDevice usbDevice;
	private UsbDeviceConnection usbConnection;
	private UsbSerialDevice serialDevice;
	private BroadcastReceiver usbReceiver;
	private PipedInputStream pis;
	private PipedOutputStream pos;

	/**
	 *
	 */
	public USBSerial() {
	}

	/**
	 * @param host
	 * @param bridge
	 * @param manager
	 */
	public USBSerial(HostBean host, TerminalBridge bridge, TerminalManager manager) {
		super(host, bridge, manager);
	}

	public static String getProtocolName() {
		return PROTOCOL;
	}

	private static boolean isUsableDevice(UsbDevice device)
	{
		int vid = device.getVendorId();
		int pid = device.getProductId();

		// Check known supported VID/PIDs

		if(FTDISioIds.isDeviceSupported(vid, pid) ||
		   CP210xIds.isDeviceSupported(vid, pid) ||
		   PL2303Ids.isDeviceSupported(vid, pid) ||
		   CH34xIds.isDeviceSupported(vid, pid))
			return true;

		// Check for CDC

		int numIfc = device.getInterfaceCount();
		for(int i=0; i<numIfc; i++)
		{
			UsbInterface ifc = device.getInterface(i);
			if(ifc.getInterfaceClass() == UsbConstants.USB_CLASS_CDC_DATA)
				return true;
		}

		return false;
	}

	private UsbDevice findDevice(String deviceId)
	{
		if (usbManager == null) {
			Log.e(TAG, "No USB manager");
			return null;
		}
		for (UsbDevice device : usbManager.getDeviceList().values()) {
			if (deviceId != null &&
			    !deviceId.equals(device.getSerialNumber()))
				continue;
			if (isUsableDevice(device)) {
				Log.d(TAG, "Found USB device "+device.getDeviceName());
				return device;
			}
		}
		return null;
	}

	private void setCommParams(String params)
	{
		int l = params.length();
		if (l>1 && params.charAt(l-1) == 'r') {
			serialDevice.setFlowControl(UsbSerialInterface.FLOW_CONTROL_RTS_CTS);
			--l;
		} else {
			serialDevice.setFlowControl(UsbSerialInterface.FLOW_CONTROL_OFF);
		}
		if (l>2 && params.charAt(l-1)>='5' && params.charAt(l-1)<='8' &&
		    params.charAt(l-2)>'9') {
			serialDevice.setDataBits(UsbSerialInterface.DATA_BITS_5+(params.charAt(l-1)-'5'));
			--l;
		} else {
                    serialDevice.setDataBits(UsbSerialInterface.DATA_BITS_8);
		}
		if (l>1 && params.charAt(l-1)>'9') {
			switch(params.charAt(l-1)) {
			case 'n':
				serialDevice.setParity(UsbSerialInterface.PARITY_NONE);
				break;
			case 'o':
				serialDevice.setParity(UsbSerialInterface.PARITY_ODD);
				break;
			case 'e':
				serialDevice.setParity(UsbSerialInterface.PARITY_EVEN);
				break;
			case 'm':
				serialDevice.setParity(UsbSerialInterface.PARITY_MARK);
				break;
			case 's':
				serialDevice.setParity(UsbSerialInterface.PARITY_SPACE);
				break;
			}
			--l;
		} else {
			serialDevice.setParity(UsbSerialInterface.PARITY_NONE);
		}
		serialDevice.setStopBits(UsbSerialInterface.STOP_BITS_1);
		serialDevice.setBaudRate(Integer.parseInt(params.substring(0, l)));
	}

	@Override
	public void onReceivedData(byte[] data) {
		if (pos != null)
			try {
				pos.write(data);
			} catch(IOException e) {
				Log.e(TAG, "Write to pipe failed", e);
			}
	}

	private void permissionDenied()
	{
		if (usbDevice == null || serialDevice != null)
			return;

		bridge.outputLine(manager.res.getString(R.string.serial_device_permission_denied));
		bridge.dispatchDisconnect(false);
	}

	private void permissionGranted()
	{
		if (usbDevice == null || serialDevice != null)
			return;

		bridge.outputLine(manager.res.getString(R.string.serial_device_permission_granted));

		usbConnection = usbManager.openDevice(usbDevice);
		if (usbConnection == null) {
			Log.e(TAG, "Cannot open UsbDevice");
		} else {
			try {
				serialDevice = UsbSerialDevice.createUsbSerialDevice(usbDevice, usbConnection, host.getPort());
			} catch(RuntimeException e) {
				Log.e(TAG, "Caught exception", e);
			}
			if (serialDevice == null) {
				Log.e(TAG, "Cannot create UsbSerialDevice");
			} else {
				if (!serialDevice.open()) {
					Log.e(TAG, "Cannot open UsbSerialDevice");
					serialDevice = null;
				}
			}
		}

		if (serialDevice == null) {
			if (usbConnection != null) {
				usbConnection.close();
				usbConnection = null;
			}
			bridge.outputLine(manager.res.getString(R.string.serial_device_open_failed));
			bridge.dispatchDisconnect(false);
		} else {
			setCommParams(host.getHostname());
			pos = new PipedOutputStream();
			try {
				pis = new PipedInputStream(pos);
			} catch(IOException e) {
				Log.d(TAG, "Failed to create PipedInputStream", e);
			}
			serialDevice.read(this);
			bridge.onConnected();
		}
	}

	@Override
	public void close() {
		if (usbReceiver != null) {
			manager.unregisterReceiver(usbReceiver);
			usbReceiver = null;
		}
		try {
			if (pos != null) {
				pos.close();
				pos = null;
			}
			if (pis != null) {
				pis.close();
				pis = null;
			}
		} catch (IOException e) {
			Log.e(TAG, "Couldn't close serial port", e);
		}
		if (serialDevice != null) {
			serialDevice.close();
			serialDevice = null;
		}
		if (usbConnection != null) {
			usbConnection.close();
			usbConnection = null;
		}
		usbDevice = null;
		usbManager = null;
	}

	@Override
	public void connect() {
		usbManager = (UsbManager) manager.getSystemService(Context.USB_SERVICE);
		usbDevice = findDevice(host.getUsername());
		if (usbDevice == null) {
			Log.e(TAG, "Cannot find serial device");
			bridge.outputLine(manager.res.getString(R.string.serial_device_unavailable));
			bridge.dispatchDisconnect(false);
			return;
		}
		if (usbManager.hasPermission(usbDevice)) {
			permissionGranted();
			return;
		}
		bridge.outputLine(manager.res.getString(R.string.serial_device_pending_permission));
		usbReceiver =
			new BroadcastReceiver() {
				@Override
					public void onReceive(Context ctx, Intent intent) {
					if (ACTION_USB_PERMISSION.equals(intent.getAction())) {
						if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
							permissionGranted();
						else
							permissionDenied();
					}
				}
			};
		final IntentFilter filter = new IntentFilter();
		filter.addAction(ACTION_USB_PERMISSION);
		manager.registerReceiver(usbReceiver, filter);
		PendingIntent pi = PendingIntent.getBroadcast(manager, 0, new Intent(ACTION_USB_PERMISSION), 0);
		usbManager.requestPermission(usbDevice, pi);
	}

	@Override
	public void flush() throws IOException {
	}

	@Override
	public String getDefaultNickname(String username, String hostname, int port) {
		if (port == DEFAULT_PORT) {
			if (username == null) {
				return String.format("%s", hostname);
			} else {
				return String.format("%s@%s", username, hostname);
			}
		} else {
			if (username == null) {
				return String.format("%s:%d", hostname, port);
			} else {
				return String.format("%s@%s:%d", username, hostname, port);
			}
		}
	}

	@Override
	public int getDefaultPort() {
		return 0;
	}

	@Override
	public boolean isConnected() {
		return usbDevice != null;
	}

	@Override
	public boolean isSessionOpen() {
		return serialDevice != null;
	}

	@Override
	public int read(byte[] buffer, int start, int len) throws IOException {
		if (pis == null) {
			bridge.dispatchDisconnect(false);
			throw new IOException("session closed");
		}
		return pis.read(buffer, start, len);
	}

	@Override
	public void setDimensions(int columns, int rows, int width, int height) {
	}

	@Override
	public void write(byte[] buffer) throws IOException {
		if (serialDevice != null)
			serialDevice.write(buffer);
	}

	@Override
	public void write(int c) throws IOException {
		if (serialDevice != null)
			serialDevice.write(new byte[] { (byte)c });
	}

	public static Uri getUri(String input) {
		Matcher matcher = baudmask.matcher(input);

		if (!matcher.matches())
			return null;

		StringBuilder sb = new StringBuilder();

		sb.append(getProtocolName())
			.append("://");

		if (matcher.group(2) != null) {
			sb.append(Uri.encode(matcher.group(2)))
				.append('@');
		}

		sb.append(matcher.group(3).toLowerCase());

		String portString = matcher.group(6);
		int port = DEFAULT_PORT;
		if (portString != null) {
			try {
				port = Integer.parseInt(portString);
				if (port < 0 || port > 255) {
					port = DEFAULT_PORT;
				}
			} catch (NumberFormatException nfe) {
				// Keep the default port
			}
		}

		if (port != DEFAULT_PORT) {
			sb.append(':')
				.append(port);
		}

		sb.append("/#")
			.append(Uri.encode(input));

		Uri uri = Uri.parse(sb.toString());

		return uri;
	}

	@Override
	public HostBean createHost(Uri uri) {
		HostBean host = new HostBean();

		host.setProtocol(PROTOCOL);
		host.setHostname(uri.getHost());
		host.setUsername(uri.getUserInfo());
		int port = uri.getPort();
		if (port < 0)
			port = DEFAULT_PORT;
		host.setPort(port);

		String nickname = uri.getFragment();
		if (nickname == null || nickname.length() == 0) {
			host.setNickname(getDefaultNickname(host.getUsername(),
					host.getHostname(), host.getPort()));
		} else {
			host.setNickname(uri.getFragment());
		}

		return host;
	}

	@Override
	public void getSelectionArgs(Uri uri, Map<String, String> selection) {
		selection.put(HostDatabase.FIELD_HOST_PROTOCOL, PROTOCOL);
		selection.put(HostDatabase.FIELD_HOST_NICKNAME, uri.getFragment());
		selection.put(HostDatabase.FIELD_HOST_HOSTNAME, uri.getHost());
		int port = uri.getPort();
		if (port < 0)
			port = DEFAULT_PORT;
		selection.put(HostDatabase.FIELD_HOST_PORT, Integer.toString(port));
		selection.put(HostDatabase.FIELD_HOST_USERNAME, uri.getUserInfo());
	}

	public static String getFormatHint(Context context) {
		return context.getString(R.string.hostpref_baudrate);
	}

	/* (non-Javadoc)
	 * @see org.connectbot.transport.AbsTransport#usesNetwork()
	 */
	@Override
	public boolean usesNetwork() {
		return false;
	}
}
