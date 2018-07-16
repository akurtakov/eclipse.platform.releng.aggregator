/*******************************************************************************
 * Copyright (c) 2000, 2018 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Anthony Dahanne  <anthony.dahanne@compuware.com> - enhance ETF to be able to launch several tests in several bundles - https://bugs.eclipse.org/330613
 *     Lucas Bullen (Red Hat Inc.) - JUnit 5 support
 *******************************************************************************/
package org.eclipse.test;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Properties;
import java.util.Timer;
import java.util.TimerTask;

import org.apache.tools.ant.Project;
import org.apache.tools.ant.taskdefs.optional.junitlauncher.TestExecutionContext;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.Status;
import org.eclipse.swt.SWT;
import org.eclipse.swt.SWTException;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.ImageLoader;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.wiring.BundleWiring;

/**
 * A TestRunner for JUnit that supports Ant JUnitResultFormatters and running
 * tests inside Eclipse. Example call: EclipseTestRunner -classname
 * junit.samples.SimpleTest
 * formatter=org.apache.tools.ant.taskdefs.optional.junit
 * .XMLJUnitResultFormatter
 */
public class EclipseTestRunner {
	static class ThreadDump extends Exception {

		private static final long serialVersionUID = 1L;

		ThreadDump(String message) {
			super(message);
		}
	}

	static class StreamForwarder extends Thread {
		private InputStream fProcessOutput;

		private PrintStream fStream;

		public StreamForwarder(InputStream processOutput, PrintStream stream) {
			fProcessOutput = processOutput;
			fStream = stream;
		}

		@Override
		public void run() {
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(fProcessOutput))) {
				String line = null;
				while ((line = reader.readLine()) != null) {
					fStream.println(line);
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
	}

	/**
	 * No problems with this test.
	 */
	public static final int SUCCESS = 0;
	/**
	 * Some tests failed.
	 */
	public static final int FAILURES = 1;
	/**
	 * An error occured.
	 */
	public static final int ERRORS = 2;

	/**
	 * SECONDS_BEFORE_TIMEOUT_BUFFER is the time we allow ourselves to take stack
	 * traces, get a screen shot, delay "SECONDS_BETWEEN_DUMPS", then do it again.
	 * On current build machine, it takes about 30 seconds to do all that, so 2
	 * minutes should be suffiencient time allowed for most machines. Though, should
	 * increase, say, if we increase the "time beteen dumps" to a minute or more.
	 */
	private static final int SECONDS_BEFORE_TIMEOUT_BUFFER = 120;

	/**
	 * SECONDS_BETWEEN_DUMPS is the time we wait from first to second dump of stack
	 * trace and screenshots. In most cases, this should suffice to determine if
	 * still busy doing something, or, hung, or waiting for user input.
	 */
	private static final int SECONDS_BETWEEN_DUMPS = 5;

	/**
	 * The main entry point (the parameters are not yet consistent with the Ant
	 * JUnitTestRunner, but eventually they should be). Parameters
	 *
	 * <pre>
	 * -className: the name of the testSuite
	 * -testPluginName: the name of the containing plugin
	 * </pre>
	 */
	public static void main(String[] args) throws IOException {
		System.exit(run(args));
	}

	public static int run(String[] args) throws IOException {
		String className = null;
		String classesNames = null;
		String testPluginName = null;
		String testPluginsNames = null;
		String timeoutString = null;
		String junitReportOutput = null;

		Properties props = new Properties();

		int startArgs = 0;
		if (args.length > 0) {
			// support the JUnit task commandline syntax where
			// the first argument is the name of the test class
			if (!args[0].startsWith("-")) {
				className = args[0];
				startArgs++;
			}
		}
		for (int i = startArgs; i < args.length; i++) {
			if (args[i].toLowerCase().equals("-classname")) {
				if (i < args.length - 1)
					className = args[i + 1];
				i++;
			} else if (args[i].toLowerCase().equals("-classesnames")) {
				if (i < args.length - 1)
					classesNames = args[i + 1];
				i++;
			} else if (args[i].toLowerCase().equals("-testpluginname")) {
				if (i < args.length - 1)
					testPluginName = args[i + 1];
				i++;
			} else if (args[i].toLowerCase().equals("-testpluginsnames")) {
				if (i < args.length - 1)
					testPluginsNames = args[i + 1];
				i++;
			} else if (args[i].equals("-junitReportOutput")) {
				if (i < args.length - 1)
					junitReportOutput = args[i + 1];
				i++;
			} else if (args[i].startsWith("haltOnError=")) {
				System.err.println("The haltOnError option is no longer supported");
			} else if (args[i].startsWith("haltOnFailure=")) {
				System.err.println("The haltOnFailure option is no longer supported");
			} else if (args[i].startsWith("formatter=")) {
				System.err.println("The formatter option is no longer supported");
			} else if (args[i].startsWith("propsfile=")) {
				try (FileInputStream in = new FileInputStream(args[i].substring(10))) {
					props.load(in);
				}
			} else if (args[i].equals("-testlistener")) {
				System.err.println("The testlistener option is no longer supported");
			} else if (args[i].equals("-timeout")) {
				if (i < args.length - 1)
					timeoutString = args[i + 1];
				i++;
			}
		}
		// Add/overlay system properties on the properties from the Ant project
		Hashtable<Object, Object> p = System.getProperties();
		for (Enumeration<Object> _enum = p.keys(); _enum.hasMoreElements();) {
			Object key = _enum.nextElement();
			props.put(key, p.get(key));
		}

		if (timeoutString == null || timeoutString.isEmpty()) {
			System.err.println("INFO: optional timeout was not specified.");
		} else {
			String timeoutScreenOutputDir = null;
			if (junitReportOutput == null || junitReportOutput.isEmpty()) {
				timeoutScreenOutputDir = "timeoutScreens";
			} else {
				timeoutScreenOutputDir = junitReportOutput + "/timeoutScreens";
			}
			System.err.println("INFO: timeoutScreenOutputDir: " + timeoutScreenOutputDir);
			System.err.println("INFO: timeout: " + timeoutString);
			startStackDumpTimeoutTimer(timeoutString, new File(timeoutScreenOutputDir), className);
		}

		if (testPluginsNames != null && classesNames != null) {
			// we have several plugins to look tests for, let's parse their
			// names
			String[] testPlugins = testPluginsNames.split(",");
			String[] suiteClasses = classesNames.split(",");
			int returnCode = 0;
			int j = 0;
			EclipseTestRunner runner = new EclipseTestRunner();
			for (String oneClassName : suiteClasses) {
				int result = runner.runTests(props, testPlugins[j], oneClassName, junitReportOutput);
				j++;
				if(result != 0) {
					returnCode = result;
				}
			}
			return returnCode;
		}
		if (className == null)
			throw new IllegalArgumentException("Test class name not specified");
		EclipseTestRunner runner = new EclipseTestRunner();
		return runner.runTests(props, testPluginName, className, junitReportOutput);
	}

	private int runTests(Properties props, String testPluginName, String testClassName, String reportOutput) {
		ClassLoader currentTCCL = Thread.currentThread().getContextClassLoader();
		ExecutionListener executionListener = new ExecutionListener();
		if(testPluginName == null) {
			testPluginName = ClassLoaderTools.getClassPlugin(testClassName);
		}
		LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
				.selectors(selectClass(testClassName))
				.build();

		try {
			Thread.currentThread().setContextClassLoader(ClassLoaderTools.getJUnit5Classloader(getPlatformEngines()));
			final Launcher launcher = LauncherFactory.create();

			Thread.currentThread().setContextClassLoader(ClassLoaderTools.getPluginClassLoader(testPluginName));
			if(reportOutput == null) {
				try(LegacyXmlResultFormatter legacyXmlResultFormatter = new LegacyXmlResultFormatter()){
					legacyXmlResultFormatter.setDestination(System.out);
					legacyXmlResultFormatter.setContext(new ExecutionContext(props));
					launcher.execute(request, legacyXmlResultFormatter, executionListener);
				} catch (IOException e) {
					e.printStackTrace();
					return ERRORS;
				}
			}else {
				try(LegacyXmlResultFormatter legacyXmlResultFormatter = new LegacyXmlResultFormatter()){
					File file = new Path(reportOutput).append(testPluginName+".xml").toFile();
					if(!file.exists())
						file.createNewFile();
					try (FileOutputStream fileOutputStream =new FileOutputStream(file)){
						legacyXmlResultFormatter.setDestination(fileOutputStream);
						legacyXmlResultFormatter.setContext(new ExecutionContext(props));
						launcher.execute(request, legacyXmlResultFormatter, executionListener);
					}
				} catch (IOException e) {
					e.printStackTrace();
					return ERRORS;
				}
			}
		} finally {
			Thread.currentThread().setContextClassLoader(currentTCCL);
		}
		return executionListener.didExecutionContainedFailures() ? FAILURES : SUCCESS;
	}


	private List<String> getPlatformEngines(){
		List<String> platformEngines = new ArrayList<>();
		Bundle bundle = FrameworkUtil.getBundle(getClass());
		Bundle[] bundles = bundle.getBundleContext().getBundles();
		for (Bundle iBundle : bundles) {
			try {
				BundleWiring bundleWiring = Platform.getBundle(iBundle.getSymbolicName()).adapt(BundleWiring.class);
				Collection<String> listResources = bundleWiring.listResources("META-INF/services", "org.junit.platform.engine.TestEngine", BundleWiring.LISTRESOURCES_LOCAL);
				if (!listResources.isEmpty())
					platformEngines.add(iBundle.getSymbolicName());
			} catch (Exception e) {
				// check the next bundle
			}
		}
		return platformEngines;
	}

	private final class ExecutionListener implements TestExecutionListener {
		private boolean executionContainedFailures;

		public ExecutionListener() {
			this.executionContainedFailures = false;
		}

		public boolean didExecutionContainedFailures() {
			return executionContainedFailures;
		}

		@Override
		public void executionFinished(TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
			if(testExecutionResult.getStatus() == org.junit.platform.engine.TestExecutionResult.Status.FAILED) {
				executionContainedFailures = true;
			}
		}
	}

	private final class ExecutionContext implements TestExecutionContext {

		private final Properties props;

		ExecutionContext(Properties props) {
			this.props = props;
		}

		@Override
		public Properties getProperties() {
			return this.props;
		}

		@Override
		public Optional<Project> getProject() {
			return null;
		}
	}

	/**
	 * Starts a timer that dumps interesting debugging information shortly before
	 * the given timeout expires.
	 *
	 * @param timeoutArg      the -timeout argument from the command line
	 * @param outputDirectory where the test results end up
	 * @param classname       the class that is running the tests suite
	 */
	private static void startStackDumpTimeoutTimer(final String timeoutArg, final File outputDirectory,
			final String classname) {
		try {
			/*
			 * The delay (in ms) is the sum of - the expected time it took for launching the
			 * current VM and reaching this method - the time it will take to run the
			 * garbage collection and dump all the infos (twice)
			 */
			int delay = SECONDS_BEFORE_TIMEOUT_BUFFER * 1000;

			int timeout = Integer.parseInt(timeoutArg) - delay;
			String time0 = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date());
			System.err.println("starting EclipseTestRunner Timer with timeout=" + timeout + " at " + time0);
			if (timeout > 0) {
				new Timer("EclipseTestRunner Timer", true).schedule(new TimerTask() {

					volatile boolean assumeUiThreadIsResponsive;

					@Override
					public void run() {
						assumeUiThreadIsResponsive = true;
						dump(0);
						try {
							Thread.sleep(SECONDS_BETWEEN_DUMPS * 1000);
						} catch (InterruptedException e) {
							// continue
						}
						dump(SECONDS_BETWEEN_DUMPS);
					}

					/**
					 *
					 * @param num num is purely a lable used in naming the screen capture files. By
					 *            convention, we pass in 0 or "SECONDS_BETWEEN_DUMPS" just as a
					 *            subtle reminder of how much time as elapsed. Thus, files end up
					 *            with names similar to <classname>_screen0.png,
					 *            <classname>_screem5.png in a directory named "timeoutScreens"
					 *            under "results", such as
					 *            .../results/linux.gtk.x86_64/timeoutScreens/
					 */
					private void dump(final int num) {
						// Time elapsed time to do each dump, so we'll
						// know if/when we get too close to the 2
						// minutes we allow
						long start = System.currentTimeMillis();

						// Dump all stacks:
						dumpStackTraces(num, System.err);
						dumpStackTraces(num, System.out); // System.err could be blocked, see
															// https://bugs.eclipse.org/506304
						logStackTraces(num);              // make this available in the log, see bug 533367

						if (!dumpSwtDisplay(num)) {
							String screenshotFile = getScreenshotFile(num);
							dumpAwtScreenshot(screenshotFile);
						}

						// Elapsed time in milliseconds
						long elapsedTimeMillis = System.currentTimeMillis() - start;

						// Print in seconds
						float elapsedTimeSec = elapsedTimeMillis / 1000F;
						System.err.println("INFO: Seconds to do dump " + num + ": " + elapsedTimeSec);
					}

					private void logStackTraces(int num) {
						ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
						dumpStackTraces(num, new PrintStream(outputStream));
						String symbolicName = "org.eclipse.test";
						IStatus warningStatus = new Status(IStatus.WARNING, symbolicName, outputStream.toString());
						Platform.getLog(Platform.getBundle(symbolicName)).log(warningStatus);
					}

					private void dumpStackTraces(int num, PrintStream out) {
						out.println("EclipseTestRunner almost reached timeout '" + timeoutArg + "'.");
						out.println("totalMemory:            " + Runtime.getRuntime().totalMemory());
						out.println("freeMemory (before GC): " + Runtime.getRuntime().freeMemory());
						out.flush(); // bug 420258: flush aggressively, we could be low on memory
						System.gc();
						out.println("freeMemory (after GC):  " + Runtime.getRuntime().freeMemory());
						String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(new Date());
						out.println("Thread dump " + num + " at " + time + ":");
						out.flush();
						Map<Thread, StackTraceElement[]> stackTraces = Thread.getAllStackTraces();
						for (Entry<Thread, StackTraceElement[]> entry : stackTraces.entrySet()) {
							String name = entry.getKey().getName();
							StackTraceElement[] stack = entry.getValue();
							ThreadDump exception = new ThreadDump("for thread \"" + name + "\"");
							exception.setStackTrace(stack);
							exception.printStackTrace(out);
						}
						out.flush();
					}

					String getScreenshotFile(final int num) {
						if (!outputDirectory.exists()) {
							outputDirectory.mkdirs();
						}
						String filename = outputDirectory.getAbsolutePath() + "/" + classname + "_screen" + num
								+ ".png";
						return filename;
					}

					@SuppressWarnings("deprecation")
					private boolean dumpSwtDisplay(final int num) {
						try {
							final Display display = Display.getDefault();

							if (!assumeUiThreadIsResponsive) {
								String message = "trying to make UI thread respond";
								IllegalStateException toThrow = new IllegalStateException(message);
								Thread t = display.getThread();
								// Initialize the cause. Its stack trace will be that of the current thread.
								toThrow.initCause(new RuntimeException(message));
								// Set the stack trace to that of the target thread.
								toThrow.setStackTrace(t.getStackTrace());
								// Stop the thread using the specified throwable.
								try {
									t.stop(toThrow);
								} catch (UnsupportedOperationException e) {
									// Thread#stop(Throwable) doesn't work any more in JDK 8. Try stop0:
									try {
										Method stop0 = Thread.class.getDeclaredMethod("stop0", Object.class);
										stop0.setAccessible(true);
										stop0.invoke(t, toThrow);
									} catch (Exception e1) {
										e1.printStackTrace();
									}
								}
							}

							assumeUiThreadIsResponsive = false;

							display.asyncExec(new Runnable() {
								@Override
								public void run() {
									assumeUiThreadIsResponsive = true;

									dumpDisplayState(System.err);
									dumpDisplayState(System.out); // System.err could be blocked, see
																	// https://bugs.eclipse.org/506304

									// Take a screenshot:
									GC gc = new GC(display);
									final Image image = new Image(display, display.getBounds());
									gc.copyArea(image, 0, 0);
									gc.dispose();

									ImageLoader loader = new ImageLoader();
									loader.data = new ImageData[] { image.getImageData() };
									String filename = getScreenshotFile(num);
									loader.save(filename, SWT.IMAGE_PNG);
									System.err.println("Screenshot saved to: " + filename);
									System.out.println("Screenshot saved to: " + filename);
									image.dispose();
								}

								private void dumpDisplayState(PrintStream out) {
									// Dump focus control, parents, and
									// shells:
									Control focusControl = display.getFocusControl();
									if (focusControl != null) {
										out.println("FocusControl: ");
										StringBuilder indent = new StringBuilder("  ");
										do {
											out.println(indent.toString() + focusControl);
											focusControl = focusControl.getParent();
											indent.append("  ");
										} while (focusControl != null);
									}
									Shell[] shells = display.getShells();
									if (shells.length > 0) {
										out.println("Shells: ");
										for (Shell shell : shells) {
											out.println((shell.isVisible() ? "  visible: " : "  invisible: ") + shell);
										}
									}
									out.flush(); // for bug 420258
								}
							});
							return true;
						} catch (SWTException e) {
							e.printStackTrace();
							return false;
						}
					}

				}, timeout);
			} else {
				System.err.println("EclipseTestRunner argument error: '-timeout " + timeoutArg
						+ "' was too short to accommodate time delay required (" + delay + ").");
			}
		} catch (NumberFormatException e) {
			e.printStackTrace();
		}
	}

	public static void dumpAwtScreenshot(String screenshotFile) {
		try {
			URL location = AwtScreenshot.class.getProtectionDomain().getCodeSource().getLocation();
			String cp = location.toURI().getPath();
			String javaHome = System.getProperty("java.home");
			String javaExe = javaHome + File.separatorChar + "bin" + File.separatorChar + "java";
			if (File.separatorChar == '\\') {
				javaExe += ".exe"; // assume it's Windows
			}
			String[] args = new String[] { javaExe, "-cp", cp, AwtScreenshot.class.getName(), screenshotFile };
			System.err.println("Start process: " + Arrays.asList(args));
			ProcessBuilder processBuilder = new ProcessBuilder(args);
			if ("Mac OS X".equals(System.getProperty("os.name"))) {
				processBuilder.environment().put("AWT_TOOLKIT", "CToolkit");
			}
			Process process = processBuilder.start();
			new StreamForwarder(process.getErrorStream(), System.err).start();
			new StreamForwarder(process.getInputStream(), System.err).start();
			int screenshotTimeout = 15;
			long end = System.currentTimeMillis() + screenshotTimeout * 1000;
			boolean done = false;
			do {
				try {
					process.exitValue();
					done = true;
				} catch (IllegalThreadStateException e) {
					try {
						Thread.sleep(100);
					} catch (InterruptedException e1) {
					}
				}
			} while (!done && System.currentTimeMillis() < end);

			if (done) {
				System.err.println("AwtScreenshot VM finished with exit code " + process.exitValue() + ".");
			} else {
				process.destroy();
				System.err.println("Killed AwtScreenshot VM after " + screenshotTimeout + " seconds.");
			}
		} catch (URISyntaxException | IOException e) {
			e.printStackTrace();
		}
	}
}
