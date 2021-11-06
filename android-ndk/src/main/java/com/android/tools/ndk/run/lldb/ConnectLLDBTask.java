//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package com.android.tools.ndk.run.lldb;

import com.android.ddmlib.AdbCommandRejectedException;
import com.android.ddmlib.Client;
import com.android.ddmlib.CollectingOutputReceiver;
import com.android.ddmlib.IDevice;
import com.android.ddmlib.ShellCommandUnresponsiveException;
import com.android.ddmlib.SyncService;
import com.android.ddmlib.TimeoutException;
import com.android.ddmlib.SyncService.FileStat;
import com.android.repository.Revision;
import com.android.sdklib.devices.Abi;
import com.android.tools.idea.ndk.NativeCompilerSetting;
import com.android.tools.idea.ndk.NativeWorkspaceService;
import com.android.tools.idea.run.AndroidProcessText;
import com.android.tools.idea.run.AndroidSessionInfo;
import com.android.tools.idea.run.ApplicationIdProvider;
import com.android.tools.idea.run.ConsolePrinter;
import com.android.tools.idea.run.LaunchInfo;
import com.android.tools.idea.run.ProcessHandlerConsolePrinter;
import com.android.tools.idea.run.tasks.ConnectDebuggerTask;
import com.android.tools.idea.run.util.LaunchStatus;
import com.android.tools.idea.run.util.ProcessHandlerLaunchStatus;
import com.android.tools.ndk.ModulePathManager;
import com.android.tools.ndk.NdkHelper;
import com.android.tools.ndk.ModulePathManager.FileWithAbi;
import com.android.tools.ndk.configuration.CxxSyncUtilsKt;
import com.android.tools.ndk.run.AndroidNativeAppDebugProcess;
import com.android.tools.ndk.run.ClientShellHelper;
import com.android.tools.ndk.run.ProgressReporter;
import com.android.tools.ndk.run.editor.AutoAndroidDebuggerState;
import com.android.tools.ndk.run.editor.HybridAndroidDebugger;
import com.android.tools.ndk.run.editor.NativeAndroidDebugger;
import com.android.tools.ndk.run.editor.NativeAndroidDebuggerState;
import com.android.tools.ndk.run.jdwp.JdwpConnector;
import com.google.common.base.Charsets;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import com.google.wireless.android.sdk.stats.AndroidStudioEvent.DebuggerType;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.configurations.CommandLineState;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.process.ProcessTerminatedListener;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.notification.NotificationGroup;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebugSessionListener;
import com.intellij.xdebugger.impl.XDebugSessionImpl;
import com.jetbrains.cidr.execution.CidrCommandLineState;
import com.jetbrains.cidr.execution.CidrLauncher;
import com.jetbrains.cidr.execution.CidrRunner;
import com.jetbrains.cidr.execution.Installer;
import com.jetbrains.cidr.execution.RunParameters;
import com.jetbrains.cidr.execution.TrivialInstaller;
import com.jetbrains.cidr.execution.debugger.CidrDebugProcess;
import com.jetbrains.cidr.execution.debugger.backend.DebuggerDriverConfiguration;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import kotlin.Pair;
import org.jetbrains.android.dom.manifest.Application;
import org.jetbrains.android.dom.manifest.Manifest;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ConnectLLDBTask extends ConnectDebuggerTask {
    private static final Logger LOG = Logger.getInstance(ConnectLLDBTask.class);
    private final String mySessionId;
    @NotNull
    private final ExecutionEnvironment myEnv;
    @NotNull
    protected final AndroidFacet myFacet;
    @NotNull
    protected final NativeAndroidDebuggerState myDebuggerState;
    @NotNull
    private final String myRunConfigTypeId;
    @NotNull
    protected final NativeAndroidDebugger myNativeDebugger;
    protected ProgressReporter myProgressReporter;
    public static final NotificationGroup NOTIFICATION_GROUP = NotificationGroup.toolWindowGroup("Native Debugger", "Debug", true, PluginId.getId("com.android.tools.ndk"));

    public ConnectLLDBTask(@NotNull ExecutionEnvironment env, @NotNull ApplicationIdProvider applicationIdProvider, @NotNull AndroidFacet facet, @NotNull NativeAndroidDebuggerState state, @NotNull NativeAndroidDebugger debugger, @NotNull String runConfigTypeId) {
        super(applicationIdProvider, debugger, facet.getModule().getProject(), false);
        this.myEnv = env;
        this.myFacet = facet;
        this.myDebuggerState = state;
        this.myRunConfigTypeId = runConfigTypeId;
        this.myNativeDebugger = debugger;
        this.mySessionId = UUID.randomUUID().toString();
    }

    public boolean isReadyForDebugging(@NotNull Client client, @NotNull ConsolePrinter printer) {
        return super.isReadyForDebugging(client, printer) && client.isDdmAware();
    }

    protected boolean isDetachOnStop() {
        return false;
    }

    protected boolean needsJdwpConnector(@NotNull ConnectLLDBTask.SessionStarterType starterType) {
        return true;
    }

    private ConnectLLDBTask.SessionStarterType decideStarterImplementation(@NotNull Client client, @NotNull ClientShellHelper clientShellHelper) throws ExecutionException {
        if (Boolean.getBoolean("lldb.session-starter.runas")) {
            LOG.warn("Using run-as to start debug session due to system property");
            return ConnectLLDBTask.SessionStarterType.RUN_AS_SHELL;
        } else if (Boolean.getBoolean("lldb.session-starter.jdwp")) {
            LOG.warn("Using injector to start debug session due to system property");
            return ConnectLLDBTask.SessionStarterType.INJECTOR;
        } else {
            boolean isInjectorSupported = client.getDevice().getVersion().getApiLevel() <= 28;
            ConnectLLDBTask.PtraceScope ptraceScope = getPtraceScope(client.getDevice());
            if (ptraceScope != ConnectLLDBTask.PtraceScope.ALLOWED) {
                LOG.warn("YAMA is on - ptrace_scope set to " + ptraceScope);
                if (ptraceScope == ConnectLLDBTask.PtraceScope.RESTRICTED && isInjectorSupported) {
                    LOG.warn("Using injector-yama to start debug session");
                    return ConnectLLDBTask.SessionStarterType.INJECTOR_YAMA;
                } else {
                    LOG.error("Unsupported device: " + this.getDeviceDisplayName(client) + "\nNative debugging is blocked on this device (ptrace_scope=" + ptraceScope + ").\nSee native debugger requirements at: https://developer.android.com/studio/debug#debug-types");
                    throw new ExecutionException("Unsupported device. This device cannot be debugged using the native debugger. See log file for details.");
                }
            } else if (isRootedDevice(client.getDevice())) {
                LOG.warn("Rooted device, using shell to start debug session");
                return ConnectLLDBTask.SessionStarterType.ROOT_SHELL;
            } else if (isRunAsOK(client, clientShellHelper)) {
                LOG.warn("Using run-as to start debug session");
                return ConnectLLDBTask.SessionStarterType.RUN_AS_SHELL;
            } else if (isInjectorSupported) {
                LOG.warn("Non-rooted device, run-as not working, resorting to injector to start debug session");
                return ConnectLLDBTask.SessionStarterType.INJECTOR;
            } else {
                LOG.error("Unsupported device (Non-rooted device, run-as not working, injector not supported): " + this.getDeviceDisplayName(client) + "\nNative debugging is not supported on this device.\nSee native debugger requirements at: https://developer.android.com/studio/debug#debug-types");
                throw new ExecutionException("Unsupported device. This device cannot be debugged using the native debugger. See log file for details.");
            }
        }
    }

    private static boolean hasBrokenNdkABI(@NotNull Client client) {
        Iterator var1 = getClientABIs(client).iterator();

        Abi abi;
        do {
            if (!var1.hasNext()) {
                return false;
            }

            abi = (Abi)var1.next();
        } while(!abi.toString().equals("x86"));

        return true;
    }

    private void maybeShowBrokenNdkMessage(@NotNull Client client) {
        if (hasBrokenNdkABI(client)) {
            NativeWorkspaceService nativeWorkspaceService = NativeWorkspaceService.Companion.getInstance(this.myProject);
            Optional<NativeCompilerSetting> anyFileSetting = nativeWorkspaceService.getCompilerSettings((moduleVariantAbi) -> {
                return true;
            }).findFirst();
            if (anyFileSetting.isPresent()) {
                File cc = ((NativeCompilerSetting)anyFileSetting.get()).getCompilerExe();
                if (cc.getName().contains("clang")) {
                    Pair<File, Revision> ndkRootAndVersion = CxxSyncUtilsKt.findNdkFolderFromFileInNdk(cc);
                    if (ndkRootAndVersion != null) {
                        if (((Revision)ndkRootAndVersion.getSecond()).getMajor() <= 16) {
                            NOTIFICATION_GROUP.createNotification("While debugging with an x86 device, the version of Clang included in NDK r16 and lower has a known issue that prevents function argument values from displaying correctly. To avoid this issue, either use an x86_64 device, or update the NDK to r17 or higher.", NotificationType.WARNING).notify(this.myProject);
                        }
                    }
                }
            }
        }
    }

    private void maybeShowMustExpandNativeLibsMessage() {
        String extractNativeLibs = (String)ReadAction.compute(() -> {
            Manifest manifest = Manifest.getMainManifest(this.myFacet);
            if (manifest == null) {
                return null;
            } else {
                Application app = manifest.getApplication();
                return app != null && app.getXmlTag() != null ? app.getXmlTag().getAttributeValue("extractNativeLibs", "http://schemas.android.com/apk/res/android") : null;
            }
        });
        if ("false".equalsIgnoreCase(extractNativeLibs)) {
            NOTIFICATION_GROUP.createNotification("Android Studio cannot debug native libraries that are not extracted from the APK. When debugging your app, set extractNativeLibs=\"true\" in your app's manifest.", NotificationType.WARNING).notify(this.myProject);
        }

    }

    @Nullable
    public ProcessHandler launchDebugger(@NotNull final LaunchInfo currentLaunchInfo, @NotNull final Client client, @NotNull final ProcessHandlerLaunchStatus launchStatus, @NotNull ProcessHandlerConsolePrinter printer) {
        ApplicationManager.getApplication().assertIsDispatchThread();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            this.launchDebuggerInBackground(currentLaunchInfo, client, launchStatus, printer);
        });
        return null;
    }

    private void launchDebuggerInBackground(@NotNull final LaunchInfo currentLaunchInfo, @NotNull final Client client, @NotNull final ProcessHandlerLaunchStatus launchStatus, @NotNull ProcessHandlerConsolePrinter printer) {
        this.maybeShowBrokenNdkMessage(client);
        this.maybeShowMustExpandNativeLibsMessage();
        this.myProgressReporter = new ProgressReporter(this.myEnv.getProject());
        String prettyConfigName = this.getPrettyConfigurationName();
        IDevice device = client.getDevice();
        String deviceModel = device.getProperty("ro.product.model");
        String deviceAPILevel = device.getProperty("ro.build.version.sdk");
        String deviceCodename = device.getProperty("ro.build.version.codename");
        String deviceManufacturer = device.getProperty("ro.product.manufacturer");
        LOG.info(String.format("Launching %s native debug session on device: manufacturer=%s, model=%s, API=%s, codename=%s, ABIs=%s", prettyConfigName, deviceManufacturer, deviceModel, deviceAPILevel, deviceCodename, device.getAbis().toString()));
        ClientShellHelper clientShellHelper = new ClientShellHelper(client, this.getPackageNameOverride(client));

        try {
            if (clientShellHelper.isRestrictedUser()) {
                checkRunAsUnderRestrictedUser(client);
            }

            ConnectLLDBTask.SessionStarterType sessionStarterType = this.decideStarterImplementation(client, clientShellHelper);
            LLDBUsageTracker.sessionStarted(device, this.getDebuggerType(), this.mySessionId, this.debuggerTypeChosenByAuto(), sessionStarterType);
            this.launchCidrDebugger(currentLaunchInfo, client, clientShellHelper, sessionStarterType, launchStatus, printer);
        } catch (Throwable var13) {
            this.onLaunchFailure(launchStatus, client, "Error while starting native debug session: " + var13.toString(), var13);
        }

    }

    private static void checkRunAsUnderRestrictedUser(@NotNull Client client) throws TimeoutException, AdbCommandRejectedException, ShellCommandUnresponsiveException, IOException {
        CollectingOutputReceiver receiver = new CollectingOutputReceiver();
        client.getDevice().executeShellCommand("run-as", receiver);
        if (!receiver.getOutput().contains("--user")) {
            throw new IllegalStateException("Native debugging under restricted user is not supported yet.");
        }
    }

    @NotNull
    private static File createPtraceScopeTempFile() throws IOException {
        File file = File.createTempFile("ptrace_scope", Long.toString(System.currentTimeMillis()));
        file.deleteOnExit();
        return file;
    }

    private static ConnectLLDBTask.PtraceScope getPtraceScope(@NotNull IDevice device) {
        SyncService syncService = null;
        File localPtraceScopeFile = null;

        try {
            syncService = device.getSyncService();
            if (syncService == null) {
                throw new ExecutionException("Failed to get SyncService");
            } else {
                FileStat ptraceScopeStat = syncService.statFile("/proc/sys/kernel/yama/ptrace_scope");
                if (ptraceScopeStat == null || ptraceScopeStat.getMode() == 0) {
                    ConnectLLDBTask.PtraceScope var21 = ConnectLLDBTask.PtraceScope.ALLOWED;
                    return var21;
                } else {
                    localPtraceScopeFile = createPtraceScopeTempFile();
                    device.pullFile("/proc/sys/kernel/yama/ptrace_scope", localPtraceScopeFile.getPath());
                    int scope = Integer.parseInt(Files.toString(localPtraceScopeFile, Charsets.UTF_8).trim());
                    ConnectLLDBTask.PtraceScope var5;
                    switch(scope) {
                        case 0:
                            var5 = ConnectLLDBTask.PtraceScope.ALLOWED;
                            return var5;
                        case 1:
                            var5 = ConnectLLDBTask.PtraceScope.RESTRICTED;
                            return var5;
                        case 2:
                            var5 = ConnectLLDBTask.PtraceScope.ADMIN_ONLY;
                            return var5;
                        default:
                            var5 = ConnectLLDBTask.PtraceScope.DISABLED;
                            return var5;
                    }
                }
            }
        } catch (Exception var19) {
            LOG.warn(var19);
            return ConnectLLDBTask.PtraceScope.ALLOWED;
        } finally {
            if (localPtraceScopeFile != null) {
                try {
                    java.nio.file.Files.delete(localPtraceScopeFile.toPath());
                } catch (IOException var18) {
                    LOG.warn(var18);
                }
            }

            if (syncService != null) {
                syncService.close();
            }

        }
    }

    @NotNull
    private SessionStarter newSessionStarter(@Nullable JdwpConnector jdwpConnector, @NotNull Client client, @NotNull ClientShellHelper clientShellHelper, @NotNull List<Abi> clientABIs, @NotNull File lldbServer, @NotNull File startServerScript, @NotNull ConnectLLDBTask.SessionStarterType type, @NotNull ProcessHandlerLaunchStatus launchStatus, @NotNull ConsolePrinter printer) throws ExecutionException {
        AndroidNativeAppDebugProcess.verifyNativeModel(this.myFacet, clientABIs, printer);
        switch(type) {
            case INJECTOR:
            case INJECTOR_YAMA:
                if (jdwpConnector == null) {
                    throw new IllegalStateException("JDWP Connector must not be null when injector session starter is required");
                }

                return this.newInjectorSessionStarter(jdwpConnector, client, clientShellHelper, type == ConnectLLDBTask.SessionStarterType.INJECTOR_YAMA, lldbServer, startServerScript, launchStatus, printer);
            case ROOT_SHELL:
                return this.newShellSessionStarter(jdwpConnector, client, clientShellHelper, lldbServer, startServerScript, launchStatus, printer);
            case RUN_AS_SHELL:
                return this.newRunAsSessionStarter(jdwpConnector, client, clientShellHelper, lldbServer, startServerScript, launchStatus, printer);
            default:
                throw new IllegalStateException("SessionStarterType was not recognized");
        }
    }

    private static void resumeVMOnSessionStarted(@NotNull SessionStarter sessionStarter, @Nullable JdwpConnector jdwpConnector) {
        if (jdwpConnector != null) {
            Objects.requireNonNull(jdwpConnector);
            sessionStarter.addEventListener(jdwpConnector::Connect);
        }
    }

    @NotNull
    private SessionStarter newShellSessionStarter(@Nullable JdwpConnector jdwpConnector, @NotNull Client client, @NotNull ClientShellHelper clientShellHelper, @NotNull File serverPath, @NotNull File startScriptPath, @NotNull ProcessHandlerLaunchStatus launchStatus, @NotNull ConsolePrinter printer) throws ExecutionException {
        SessionStarter sessionStarter = new ShellSessionStarterImpl(client, clientShellHelper, serverPath, startScriptPath, this.myDebuggerState, this.myProgressReporter, launchStatus, printer);
        resumeVMOnSessionStarted(sessionStarter, jdwpConnector);
        return sessionStarter;
    }

    @NotNull
    private SessionStarter newRunAsSessionStarter(@Nullable JdwpConnector jdwpConnector, @NotNull Client client, @NotNull ClientShellHelper clientShellHelper, @NotNull File serverPath, @NotNull File startScriptPath, @NotNull ProcessHandlerLaunchStatus launchStatus, @NotNull ConsolePrinter printer) throws ExecutionException {
        SessionStarter sessionStarter = new RunAsSessionStarterImpl(client, clientShellHelper, serverPath, startScriptPath, this.myDebuggerState, this.myProgressReporter, launchStatus, printer);
        resumeVMOnSessionStarted(sessionStarter, jdwpConnector);
        return sessionStarter;
    }

    @NotNull
    private SessionStarter newInjectorSessionStarter(@NotNull JdwpConnector jdwpConnector, @NotNull Client client, @NotNull ClientShellHelper clientShellHelper, boolean restrictedPtraceScope, @NotNull File serverPath, @NotNull File startScriptPath, @NotNull ProcessHandlerLaunchStatus launchStatus, @NotNull ConsolePrinter printer) throws ExecutionException {
        return new InjectorSessionStarterImpl(jdwpConnector, client, clientShellHelper, restrictedPtraceScope, serverPath, startScriptPath, this.myDebuggerState, this.myProgressReporter, launchStatus, printer);
    }

    private static boolean isRunAsOK(@NotNull Client client, @NotNull ClientShellHelper clientShellHelper) throws ExecutionException {
        try {
            IDevice device = client.getDevice();
            String deviceModel = device.getProperty("ro.product.model");
            if (deviceModel == null) {
                return false;
            } else {
                CollectingOutputReceiver receiver = new CollectingOutputReceiver();
                String runAsCommand = clientShellHelper.getRunAsCommand(String.format("getprop %s", "ro.product.model"));
                client.getDevice().executeShellCommand(runAsCommand, receiver);
                String output = receiver.getOutput().trim();
                if (!output.contains(deviceModel)) {
                    LOG.warn("run-as for the selected device appears to be broken, output was : " + output);
                    return false;
                } else {
                    return true;
                }
            }
        } catch (Exception var7) {
            throw new ExecutionException(var7);
        }
    }

    protected static boolean isRootedDevice(@NotNull IDevice device) throws ExecutionException {
        try {
            return device.isRoot();
        } catch (Exception var2) {
            throw new ExecutionException(var2);
        }
    }

    private static int getClientAddressByteSize(@NotNull final Client client) {
        String abi = client.getClientData().getAbi();
        return abi != null && abi.startsWith("64-bit") ? 8 : 4;
    }

    @NotNull
    private static List<Abi> getClientABIs(@NotNull final Client client) {
        int clientAddrByteSize = getClientAddressByteSize(client);
        if (clientAddrByteSize <= 0) {
            LOG.warn("Failed to get client address byte size from ABI: " + client.getClientData().getAbi());
        }

        List<Abi> abis = Lists.newLinkedList();
        IDevice device = client.getDevice();
        Iterator var4 = device.getAbis().iterator();

        while(var4.hasNext()) {
            String abiStr = (String)var4.next();
            Abi abi = Abi.getEnum(abiStr);
            if (abi == null) {
                LOG.warn("Failed to get abi by name: " + abiStr);
            } else if (clientAddrByteSize > 0) {
                if (abi.getAddressSizeInBytes() == clientAddrByteSize) {
                    abis.add(abi);
                }
            } else {
                abis.add(abi);
            }
        }

        LOG.info("ABIs supported by app: " + abis.toString());
        return abis;
    }

    @NotNull
    private RunParameters newRunParameters(@NotNull final Client client, @NotNull List<Abi> clientABIs, @NotNull final SessionStarter sessionStarter, @NotNull final ProcessHandlerConsolePrinter printer, @NotNull final Abi targetAbi) {
        final IDevice device = client.getDevice();
        final AndroidLLDBDriverConfiguration configuration = new AndroidLLDBDriverConfiguration(this.myFacet, this.myDebuggerState, printer, device, clientABIs, sessionStarter, this.getStartupCommands(client, printer, targetAbi), this.getPostAttachCommands(client));
        return new RunParameters() {
            @NotNull
            public Installer getInstaller() {
                return new TrivialInstaller(new GeneralCommandLine(new String[]{""}));
            }

            @NotNull
            public DebuggerDriverConfiguration getDebuggerDriverConfiguration() {
                return configuration;
            }

            @NotNull
            public String getArchitectureId() {
                return NdkHelper.getArchitectureId(NdkHelper.getAbi(device));
            }
        };
    }

    @NotNull
    protected JdwpConnector newJdwpConnector(@NotNull LaunchInfo currentLaunchInfo, @NotNull Client client, @NotNull ClientShellHelper clientShellHelper, @NotNull XDebugSession session) {
        return new JdwpConnector(currentLaunchInfo, this.myFacet, client, clientShellHelper, session, true);
    }

    private void launchCidrDebugger(@NotNull final LaunchInfo currentLaunchInfo, @NotNull final Client client, @NotNull final ClientShellHelper clientShellHelper, @NotNull final ConnectLLDBTask.SessionStarterType sessionStarterType, @NotNull final ProcessHandlerLaunchStatus launchStatus, @NotNull final ProcessHandlerConsolePrinter printer) {
        CidrRunner cidrRunner = new CidrRunner() {
            @NotNull
            public String getRunnerId() {
                return "AndroidNativeDebugRunner2";
            }
        };
        ExecutionEnvironment env = (new ExecutionEnvironmentBuilder(this.myEnv)).executor(this.myEnv.getExecutor()).runner(cidrRunner).contentToReuse(this.myEnv.getContentToReuse()).build();
        CidrCommandLineState cidrState = new CidrCommandLineState(env, new CidrLauncher() {
            public ProcessHandler createProcess(@NotNull CommandLineState state) {
                throw new RuntimeException("start process not implemented");
            }

            @NotNull
            public CidrDebugProcess createDebugProcess(@NotNull CommandLineState state, @NotNull XDebugSession session) throws ExecutionException {
                List<Abi> clientABIs = ConnectLLDBTask.getClientABIs(client);
                JdwpConnector jdwpConnector = null;
                if (!ConnectLLDBTask.this.needsJdwpConnector(sessionStarterType)) {
                    ConnectLLDBTask.LOG.info("Not creating JDWP connector.  Connect task is : " + ConnectLLDBTask.this.getClass().getName() + ". Session starter type is : " + sessionStarterType + ". Debugger id is : " + ConnectLLDBTask.this.myNativeDebugger.getId());
                } else {
                    jdwpConnector = ConnectLLDBTask.this.newJdwpConnector(currentLaunchInfo, client, clientShellHelper, session);
                }

                FileWithAbi lldbServer = ModulePathManager.findLLDBServer(ConnectLLDBTask.this.myFacet, ConnectLLDBTask.this.myDebuggerState, clientABIs);
                File startServerScript = ModulePathManager.getLldbAndroidFile("start_lldb_server.sh");
                if (lldbServer == null) {
                    ConnectLLDBTask.LOG.error("LLDB server not found");
                    throw new ExecutionException(String.format("LLDB server for architectures [%s] not found", clientABIs));
                } else {
                    ConnectLLDBTask.LOG.info(String.format("Found LLDB server: \"%s\"", lldbServer.getFile().getAbsolutePath()));

                    SessionStarter sessionStarter;
                    try {
                        sessionStarter = ConnectLLDBTask.this.newSessionStarter(jdwpConnector, client, clientShellHelper, clientABIs, lldbServer.getFile(), startServerScript, sessionStarterType, launchStatus, printer);
                    } catch (Exception var10) {
                        throw new ExecutionException(var10);
                    }

                    RunParameters runParameters = ConnectLLDBTask.this.newRunParameters(client, clientABIs, sessionStarter, printer, lldbServer.getAbi());
                    AndroidNativeAppDebugProcess result = ConnectLLDBTask.this.myNativeDebugger.newAndroidNativeAppDebugProcess(ConnectLLDBTask.this.mySessionId, runParameters, session, state.getConsoleBuilder(), printer, sessionStarter, client, jdwpConnector, ConnectLLDBTask.this.myProgressReporter, ConnectLLDBTask.this.isDetachOnStop(), lldbServer.getAbi());
                    ProcessTerminatedListener.attach(result.getProcessHandler(), ConnectLLDBTask.this.myEnv.getProject());
                    return result;
                }
            }

            @NotNull
            public Project getProject() {
                return ConnectLLDBTask.this.myEnv.getProject();
            }
        });
        printer.stdout("Now Launching Native Debug Session");
        this.myProgressReporter.step("Launching debug session");
        ApplicationManager.getApplication().invokeLater(() -> {
            XDebugSessionImpl xDebugSession;
            try {
                xDebugSession = (XDebugSessionImpl)cidrRunner.startDebugSession(cidrState, env, false, new XDebugSessionListener[0]);
            } catch (Throwable var9) {
                this.onLaunchFailure(launchStatus, client, "Error while starting native debug session: " + var9.toString(), var9);
                return;
            }

            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                ProcessHandler oldProcessHandler = launchStatus.getProcessHandler();
                ProcessHandler newProcessHandler = xDebugSession.getRunContentDescriptor().getProcessHandler();

                try {
                    if (newProcessHandler == null) {
                        throw new ExecutionException("Cannot start debugging - null process handler.");
                    }
                } catch (Throwable var11) {
                    this.onLaunchFailure(launchStatus, client, "Error while starting native debug session: " + var11.toString(), var11);
                    return;
                }

                AndroidSessionInfo oldInfo = (AndroidSessionInfo)oldProcessHandler.getUserData(AndroidSessionInfo.KEY);
                if (oldInfo != null) {
                    AndroidSessionInfo.create(newProcessHandler, oldInfo.getDescriptor(), oldInfo.getRunConfiguration(), oldInfo.getExecutorId(), oldInfo.getExecutorActionName(), oldInfo.getExecutionTarget());
                } else {
                    RunProfile runProfile = env.getRunProfile();
                    RunConfiguration runConfiguration = runProfile instanceof RunConfiguration ? (RunConfiguration)runProfile : null;
                    AndroidSessionInfo.create(newProcessHandler, xDebugSession.getRunContentDescriptor(), runConfiguration, env.getExecutor().getId(), env.getExecutor().getActionName(), env.getExecutionTarget());
                }

                launchStatus.setProcessHandler(newProcessHandler);
                printer.setProcessHandler(newProcessHandler);
                oldProcessHandler.detachProcess();
                AndroidProcessText oldText = AndroidProcessText.get(oldProcessHandler);
                if (oldText != null) {
                    oldText.printTo(newProcessHandler);
                }

                ApplicationManager.getApplication().invokeLater(xDebugSession::showSessionTab);
            });
        });
    }

    @NotNull
    private String getPrettyConfigurationName() {
        List<String> items = Lists.newArrayList();
        items.add(this.myRunConfigTypeId);
        items.add(this.myDebugger.getId());
        return StringUtil.join(items, ":");
    }

    private void onLaunchFailure(@NotNull LaunchStatus launchStatus, @NotNull Client client, @NotNull String message, @NotNull Throwable e) {
        launchStatus.terminateLaunch(message, true);
        LOG.warn(message, e);
        LLDBUsageTracker.sessionFailed(e, this.mySessionId, 0L, 1L);
        this.myProgressReporter.finish();
        this.forceStopActivity(client);
        NOTIFICATION_GROUP.createNotification("Failed to launch native debugger.", "", e.getMessage(), NotificationType.ERROR).notify(this.myProject);
    }

    private void forceStopActivity(@NotNull Client client) {
        String packageName = this.getPackageNameOverride(client);
        if (packageName == null) {
            packageName = client.getClientData().getPackageName();
        }

        try {
            IDevice device = client.getDevice();
            CollectingOutputReceiver receiver = new CollectingOutputReceiver();
            device.executeShellCommand("am force-stop " + packageName, receiver);
        } catch (Exception var5) {
            LOG.info("Failed to force-stop activity " + packageName, var5);
        }

    }

    @NotNull
    private List<String> getStartupCommands(@NotNull Client client, @NotNull ProcessHandlerConsolePrinter printer, @NotNull Abi targetAbi) {
        return (List)Stream.concat(this.myNativeDebugger.getStartupCommands(this.myFacet, client, printer, targetAbi).stream(), this.myDebuggerState.getUserStartupCommands().stream()).collect(Collectors.toList());
    }

    @NotNull
    private List<String> getPostAttachCommands(@NotNull Client client) {
        return (List)Stream.concat(this.myNativeDebugger.getPostAttachCommands(this.myFacet, client).stream(), this.myDebuggerState.getUserPostAttachCommands().stream()).collect(Collectors.toList());
    }

    private boolean debuggerTypeChosenByAuto() {
        return this.myDebuggerState instanceof AutoAndroidDebuggerState;
    }

    private DebuggerType getDebuggerType() {
        if (this.myDebugger instanceof HybridAndroidDebugger) {
            return DebuggerType.HYBRID;
        } else {
            return this.myDebugger instanceof NativeAndroidDebugger ? DebuggerType.NATIVE : DebuggerType.UNKNOWN_DEBUGGER_TYPE;
        }
    }

    @NotNull
    private String getDeviceDisplayName(@NotNull Client client) {
        IDevice device = client.getDevice();
        String deviceModel = device.getProperty("ro.product.model");
        String deviceAPILevel = device.getProperty("ro.build.version.sdk");
        String deviceManufacturer = device.getProperty("ro.product.manufacturer");
        return String.format("manufacturer=%s, model=%s, API=%s", deviceManufacturer, deviceModel, deviceAPILevel);
    }

    @Nullable
    private String getPackageNameOverride(@NotNull Client client) {
        return this.myApplicationIds.contains(client.getClientData().getPackageName()) ? null : (String)this.myApplicationIds.get(0);
    }

    private static enum PtraceScope {
        ALLOWED,
        RESTRICTED,
        ADMIN_ONLY,
        DISABLED;

        private PtraceScope() {
        }
    }

    public static enum SessionStarterType {
        ROOT_SHELL,
        RUN_AS_SHELL,
        INJECTOR,
        INJECTOR_YAMA;

        private SessionStarterType() {
        }
    }
}
