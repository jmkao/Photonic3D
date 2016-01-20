package org.area515.resinprinter.client;

import java.awt.BorderLayout;
import java.awt.Desktop;
import java.io.IOException;
import java.net.SocketException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;

import org.area515.resinprinter.client.SubnetScanner.Box;
import org.fourthline.cling.UpnpService;
import org.fourthline.cling.UpnpServiceImpl;
import org.fourthline.cling.model.message.header.STAllHeader;
import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.RemoteDevice;

import com.jcraft.jsch.JSchException;

public class Main {
	public static final String PRINTER_TYPE = "3DPrinterHost";
	
	private static Set<PrintableDevice> foundDevices = new HashSet<PrintableDevice>();
	private static long maxLengthToWait = 5000;
	private static long maxLengthToWaitForAll = 7000;
	
	public static class PrintableDevice {
		public Device device;
		
		public PrintableDevice(Device device) {
			this.device = device;
		}
		
		public String toString() {
			return device.getDisplayString() + " (" + device.getDetails().getPresentationURI() + ")";
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((device == null) ? 0 : device.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			PrintableDevice other = (PrintableDevice) obj;
			if (device == null) {
				if (other.device != null)
					return false;
			} else if (!device.equals(other.device))
				return false;
			return true;
		}
	}
	
	private static char[] getPassword(String title, String prompt) {
		JPanel panel = new JPanel(new BorderLayout());
		JLabel label = new JLabel(prompt);
		JPasswordField pass = new JPasswordField(10);
		panel.add(label, BorderLayout.CENTER);
		panel.add(pass, BorderLayout.SOUTH);
		String[] options = new String[]{"OK", "Cancel"};
		int option = JOptionPane.showOptionDialog(null, panel, title, JOptionPane.NO_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[1]);
		if(option == 0) {
		    char[] password = pass.getPassword();
		    return password;
		}
		
		return null;
	}
	
	private static boolean findSuccessLine(String[] lines, String containsLine) {
		for (String line : lines) {
			if (line.contains(containsLine)) {
				return true;
			}
		}
		
		return false;
	}
	
	private static void writeOutput(String[] lines) {
		for (String line : lines) {
			System.out.println(line);
		}
	}
	
	public static boolean performInstall(Box box) throws IOException, JSchException  {
		System.out.println("User chose install on:" + box);
		
		final JOptionPane installOptionPane = new JOptionPane("Installing CWH...", JOptionPane.INFORMATION_MESSAGE, JOptionPane.CANCEL_OPTION, null, new String[]{"Cancel"}, "Cancel");
		JDialog installPane = null;
		installPane = new JDialog();
		installPane.setTitle("Installing CWH");
		installPane.setResizable(false);
		installPane.setAlwaysOnTop(true);
		installPane.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
		installPane.add(installOptionPane);
		installPane.pack();
		installPane.setLocationRelativeTo(null);
		installPane.setVisible(true);
		
		SSHClient client = new SSHClient();
		try {
			installOptionPane.setMessage("Connecting to printer...");
			client.connect("pi", "raspberry", box.getName(), 22);
			System.out.println("sudo required:" + client.sudoIfNotRoot());
			client.send("rm start.sh");
			
			installOptionPane.setMessage("Downloading installation scripts...");
			String[] output = client.send("wget https://github.com/area515/Creation-Workshop-Host/raw/master/host/bin/start.sh");
			if (!findSuccessLine(output, "start.sh' saved")) {
				writeOutput(output);
				throw new IOException("This device can't seem to reach the internet.");
			}
			output = client.send("chmod 777 *.sh");
			
			installOptionPane.setMessage("Executing installation scripts...");
			output = client.send("./start.sh");
			if (!findSuccessLine(output, "Starting printer host server")) {
				throw new IOException("There was a problem installing CWH. Please refer to logs.");
			}
			
			installPane.setVisible(false);
			char[] password = getPassword("Please Secure This Device", 
					"<html>This device was setup with a default password<br>" + 
					"from the manufacturer. It is not advisable that<br>" + 
					"you keep this password. Please enter another<br>" + 
					"password to help secure your printer.<html>");
			if (password == null) {
				return true;
			}
			
			output = client.send("echo 'pi:" + new String(password) + "' | chpasswd");
			//JOptionPane.showMessageDialog(null, "Failed to set password. " + output[0], "Bad Password", JOptionPane.WARNING_MESSAGE);
			return true;

		} finally {
			installPane.setVisible(false);
			client.disconnect();
		}
	}
	
	/**
	 * return 0 for printer found
	 * returns -1 for user cancelled operation
	 * returns -2 for error during operation
	 * 
	 * @param args
	 */
	public static void main(String[] args) {
		boolean askedUserToInstallCWH = false;
		SubnetScanner scanner = new SubnetScanner();
		try {
			scanner.startSubnetScan();
		} catch (SocketException e) {
			e.getStackTrace();//No network available?
		}
		
		JDialog searchPane = null;
		final JOptionPane searchOptionPane = new JOptionPane("Searching for all 3d printers on network...", JOptionPane.INFORMATION_MESSAGE, JOptionPane.CANCEL_OPTION, null, new String[]{"Cancel"}, "Cancel");

		do {
			final CountDownLatch waitForURLFound = new CountDownLatch(1);
			
			Thread searchThread = new Thread(new Runnable() {
				@Override
				public void run() {
					try {
						UpnpService upnpService = new UpnpServiceImpl();
						upnpService.getControlPoint().search(new STAllHeader());
						long timeStarted = System.currentTimeMillis();
						while ((foundDevices.size() > 0 && System.currentTimeMillis() - timeStarted < maxLengthToWait) ||
							   (foundDevices.size() == 0 && System.currentTimeMillis() - timeStarted < maxLengthToWaitForAll)) {
							if (searchOptionPane.getValue() != null && searchOptionPane.getValue().equals("Cancel")) {
								System.exit(-1);
							}
							
							Collection<RemoteDevice> devices = upnpService.getRegistry().getRemoteDevices();
							for (Device currentDevice : devices) {
								if (currentDevice.getType().getType().equals(PRINTER_TYPE)) {
									foundDevices.add(new PrintableDevice(currentDevice));
									System.out.println("Found printer URL here:" + currentDevice.getDetails().getPresentationURI());
								}
							}
							
							Thread.currentThread().sleep(300);
						}
	
						waitForURLFound.countDown();
						upnpService.shutdown();
					} catch (Throwable e) {
						if (foundDevices.size() == 0) {
							e.printStackTrace();
							System.exit(-2);
						}
					}
				}
			});
			searchThread.start();
			
			try {
				waitForURLFound.await(maxLengthToWaitForAll + 2000, TimeUnit.MILLISECONDS);
			} catch (InterruptedException e1) {
				//Unlikely to happen
			}
			
			boolean installCompleted = false;
			if (foundDevices.size() == 0 && !askedUserToInstallCWH) {
				try {
					List<Box> boxes = scanner.waitForDevicesWithPossibleRemoteInstallCapability();
					if (boxes.size() > 0) {
						Box box = (Box)JOptionPane.showInputDialog(null, 
						        "I couldn't find CWH installed on your network.\n"
						        + "I did find place(s) where I might be able to install it.\n"
						        + "Choose any of the following locations to install CWH.\n"
						        + "Click 'Cancel' if you've already installed CWH.", 
						        "Install CWH?",
						        JOptionPane.QUESTION_MESSAGE,
						        null,
						        boxes.toArray(),
						        boxes.iterator().next());
						if (box != null) {
							try {
								if (!performInstall(box)) {
									System.exit(-1);
								}
							} catch (JSchException | IOException e) {
								e.printStackTrace();
								JOptionPane.showConfirmDialog(null, "Unable To Install CWH", e.getMessage(), JOptionPane.ERROR);
								System.exit(-2);
							}
						}
						installCompleted = true;
					}
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				
				if (!installCompleted) {
					System.out.println("3d printer not found after waiting:" + maxLengthToWaitForAll);
					JOptionPane optionPane = new JOptionPane();
					optionPane.setMessage("Couldn't find your printer on this network.\n"
							+ "Make sure your printer has been started and it is advertising on this network.\n"
							+ "If you just configured WIFI capabilities and you are waiting for the printer to restart,\n"
							+ "then click 'Ok' to try this operation again.");
					optionPane.setMessageType(JOptionPane.ERROR_MESSAGE);
					optionPane.setOptionType(JOptionPane.OK_CANCEL_OPTION);
					
					JDialog dialog = optionPane.createDialog("Couldn't Find 3D Printer");
					dialog.setResizable(false);
					dialog.setModal(true);
					dialog.setAlwaysOnTop(true);
					dialog.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
				
					if (searchPane != null) {
						searchPane.setVisible(false);
					}
					dialog.setVisible(true);
					
					if (optionPane.getValue() == null ||
						optionPane.getValue().equals(JOptionPane.CANCEL_OPTION)) {
						System.exit(-1);
					}
				}
				
				searchPane = new JDialog();
				searchPane.setTitle("Searching for Printer");
				searchPane.setResizable(false);
				searchPane.setAlwaysOnTop(true);
				searchPane.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
				searchPane.add(searchOptionPane);
				searchPane.pack();
				searchPane.setLocationRelativeTo(null);
				searchPane.setVisible(true);
			}
		} while (foundDevices.size() == 0);
		
		if (!Desktop.isDesktopSupported()) {
			for (PrintableDevice device : foundDevices) {
				System.out.println("The printer host software is at the URL:" + device.device.getDetails().getPresentationURI());
			}
		}
		
		PrintableDevice chosenPrinter = null;
		if (foundDevices.size() == 1) {
			chosenPrinter = foundDevices.iterator().next();
		} else {
			chosenPrinter = (PrintableDevice)JOptionPane.showInputDialog(null, 
		        "There were multiple 3d printers found on this network.\nWhich printer would you like to view?", 
		        "Choose a Printer",
		        JOptionPane.QUESTION_MESSAGE,
		        null,
		        foundDevices.toArray(),
		        foundDevices.iterator().next());
			
			if (chosenPrinter == null) {
				System.exit(-1);
			}
		}
		
	    System.out.println("User chose:" + chosenPrinter.device.getDisplayString());
	    
		try {
			Desktop.getDesktop().browse(chosenPrinter.device.getDetails().getPresentationURI());
			System.exit(0);
		} catch (IOException e) {
			JOptionPane optionPane = new JOptionPane();
			optionPane.setMessage("There was a problem starting the default browser for this machine.");
			optionPane.setMessageType(JOptionPane.ERROR_MESSAGE);
			JDialog dialog = optionPane.createDialog("Browser Not Found");
			dialog.setAlwaysOnTop(true);
			dialog.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
			dialog.setVisible(true);
			System.exit(-2);
		}
	}
}
