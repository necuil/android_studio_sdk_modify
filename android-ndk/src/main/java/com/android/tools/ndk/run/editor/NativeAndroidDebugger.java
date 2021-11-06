//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package com.android.tools.ndk.run.editor;

import com.android.ddmlib.Client;
import com.android.sdklib.AndroidVersion;
import com.android.sdklib.devices.Abi;
import com.android.tools.idea.flags.StudioFlags;
import com.android.tools.idea.gradle.project.GradleProjectInfo;
import com.android.tools.idea.model.AndroidModel;
import com.android.tools.idea.model.TestExecutionOption;
import com.android.tools.idea.run.AndroidRunConfiguration;
import com.android.tools.idea.run.ApkProviderUtil;
import com.android.tools.idea.run.ApkProvisionException;
import com.android.tools.idea.run.ApplicationIdProvider;
import com.android.tools.idea.run.ConsolePrinter;
import com.android.tools.idea.run.ConsoleProvider;
import com.android.tools.idea.run.ProcessHandlerConsolePrinter;
import com.android.tools.idea.run.editor.AndroidDebuggerConfigurable;
import com.android.tools.idea.run.editor.AndroidDebuggerImplBase;
import com.android.tools.idea.run.editor.AndroidDebuggerInfoProvider;
import com.android.tools.idea.run.editor.AndroidDebuggerState;
import com.android.tools.idea.run.tasks.DebugConnectorTask;
import com.android.tools.idea.testartifacts.instrumented.orchestrator.OrchestratorUtilsKt;
import com.android.tools.ndk.NdkHelper;
import com.android.tools.ndk.run.AndroidNativeAppDebugProcess;
import com.android.tools.ndk.run.ProgressReporter;
import com.android.tools.ndk.run.attach.AndroidNativeAttachConfiguration;
import com.android.tools.ndk.run.attach.AndroidNativeAttachConfigurationType;
import com.android.tools.ndk.run.jdwp.JdwpConnector;
import com.android.tools.ndk.run.lldb.ConnectLLDBTask;
import com.android.tools.ndk.run.lldb.SessionStarter;
import com.google.common.collect.Lists;
import com.intellij.debugger.impl.DebuggerSession;
import com.intellij.execution.ExecutionException;
import com.intellij.execution.Executor;
import com.intellij.execution.ProgramRunnerUtil;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.ConfigurationFactory;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.filters.TextConsoleBuilder;
import com.intellij.execution.process.ProcessHandler;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.ui.RunContentDescriptor;
import com.intellij.facet.ProjectFacetManager;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.NotNullFunction;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.xdebugger.XDebugProcess;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebuggerManager;
import com.jetbrains.cidr.execution.RunParameters;
import com.jetbrains.cidr.execution.debugger.CidrDebuggerSettings;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Map.Entry;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class NativeAndroidDebugger extends AndroidDebuggerImplBase<NativeAndroidDebuggerState> {
    public static final String ID = "Native";
    private static final String RUN_CONFIGURATION_NAME_PATTERN = "Android %s Debugger (%d)";
    private static final Logger LOG = Logger.getInstance(NativeAndroidDebugger.class);
    private static ConsoleProvider ourConsoleProvider = null;

    public NativeAndroidDebugger() {
    }

    public static void setConsoleProvider(@NotNull ConsoleProvider consoleProvider) {
        ourConsoleProvider = consoleProvider;
    }

    protected static ConsoleProvider getConsoleProvider() {
        return ourConsoleProvider;
    }

    @NotNull
    public String getId() {
        return "Native";
    }

    @NotNull
    public String getDisplayName() {
        return "Native Only";
    }

    @NotNull
    public NativeAndroidDebuggerState createState() {
        return new NativeAndroidDebuggerState();
    }

    @NotNull
    public AndroidDebuggerConfigurable<NativeAndroidDebuggerState> createConfigurable(@NotNull RunConfiguration runConfiguration) {
        return new NativeAndroidDebuggerConfigurable(runConfiguration.getProject(), runConfiguration instanceof AndroidRunConfiguration);
    }

    @NotNull
    public DebugConnectorTask getConnectDebuggerTask(@NotNull ExecutionEnvironment env, @Nullable AndroidVersion version, @NotNull ApplicationIdProvider applicationIdProvider, @NotNull AndroidFacet facet, @NotNull NativeAndroidDebuggerState state, @NotNull String runConfigTypeId) {
        CidrDebuggerSettings.getInstance().VALUES_FILTER_ENABLED = false;
        CidrDebuggerSettings.getInstance().LLDB_NATVIS_RENDERERS_ENABLED = (Boolean)StudioFlags.ENABLE_LLDB_NATVIS.get();
        ConnectLLDBTask baseConnector = new ConnectLLDBTask(env, applicationIdProvider, facet, state, this, runConfigTypeId);
        TestExecutionOption executionType = (TestExecutionOption)Optional.ofNullable(AndroidModel.get(facet)).map(AndroidModel::getTestExecutionOption).orElse(TestExecutionOption.HOST);
        switch(executionType) {
            case ANDROID_TEST_ORCHESTRATOR:
            case ANDROIDX_TEST_ORCHESTRATOR:
                return OrchestratorUtilsKt.createReattachingDebugConnectorTask(baseConnector, executionType);
            default:
                return baseConnector;
        }
    }

    public boolean supportsProject(@NotNull Project project) {
        return GradleProjectInfo.getInstance(project).isBuildWithGradle() || NdkHelper.isNdkProject(project);
    }

    public void attachToClient(@NotNull Project project, @NotNull Client client, @Nullable RunConfiguration config) {
        String processName = client.getClientData().getClientDescription();
        if (processName != null) {
            Module module = this.findModuleForProcess(project, processName);
            if (!this.hasExistingSession(project, client)) {
                DebuggerSession debuggerSession = findJdwpDebuggerSession(project, getClientDebugPort(client));
                if (debuggerSession != null) {
                    debuggerSession.getProcess().stop(false);
                }

                RunnerAndConfigurationSettings settings = this.createRunnerAndConfigurationSettings(project, module, client, this.getAndroidDebuggerState(config));
                Executor executor = DefaultDebugExecutor.getDebugExecutorInstance();
                ApplicationManager.getApplication().invokeLater(() -> {
                    ProgramRunnerUtil.executeConfiguration(settings, executor);
                });
            }
        }
    }

    @Nullable
    protected Module findModuleForProcess(Project project, String packageName) {
        List<AndroidFacet> facets = ProjectFacetManager.getInstance(project).getFacets(AndroidFacet.ID);

        for (AndroidFacet facet: facets) {
            try {
                String facetPackageName = ApkProviderUtil.computePackageName(facet);
                if (packageName.startsWith(facetPackageName)) {
                    return facet.getModule();
                }

                if (ProcessNameReader.hasGlobalProcess(facet, packageName)) {
                    return facet.getModule();
                }
            } catch (ApkProvisionException var7) {
                LOG.warn(var7);
            }
        }

        return null;
    }

    @NotNull
    protected RunnerAndConfigurationSettings createRunnerAndConfigurationSettings(@NotNull Project project, @Nullable Module module, @NotNull Client client, @Nullable AndroidDebuggerState state) {
        String runConfigurationName = "Android " + getDisplayName() + " (" + client.getClientData().getPid() + ") [" + client.getClientData().getClientDescription() + "]";
        if (module == null) {
            runConfigurationName += " [third party]";
            if(state != null){
                module = state.getDebuggeeModule();
            }
            if(module == null){
                module = ProjectFacetManager.getInstance(project).getFacets(AndroidFacet.ID).get(0).getModule();
            }
        }
        ConfigurationFactory factory = AndroidNativeAttachConfigurationType.getInstance().getFactory();
        RunnerAndConfigurationSettings runSettings = RunManager.getInstance(project).createRunConfiguration(runConfigurationName, factory);
        AndroidNativeAttachConfiguration configuration = (AndroidNativeAttachConfiguration) runSettings.getConfiguration();
        configuration.setClient(client);
        configuration.getAndroidDebuggerContext().setDebuggerType(this.getId());
        configuration.getConfigurationModule().setModule(module);
        configuration.setConsoleProvider(ourConsoleProvider);
        NativeAndroidDebuggerState targetState = (NativeAndroidDebuggerState)configuration.getAndroidDebuggerContext().getAndroidDebuggerState();
        if (state instanceof NativeAndroidDebuggerState) {
            NativeAndroidDebuggerState originState = (NativeAndroidDebuggerState)state;

            assert targetState != null;

            targetState.setSymbolDirs(originState.getSymbolDirs());
            targetState.setUserStartupCommands(originState.getUserStartupCommands());
            targetState.setUserPostAttachCommands(originState.getUserPostAttachCommands());
        }

        return runSettings;
    }

    @Nullable
    protected AndroidDebuggerState getAndroidDebuggerState(@Nullable RunConfiguration config) {
        if (config != null) {
            AndroidDebuggerInfoProvider[] var2 = (AndroidDebuggerInfoProvider[])AndroidDebuggerInfoProvider.EP_NAME.getExtensions();

            for (AndroidDebuggerInfoProvider provider : var2) {
                if (provider.supportsProject(config.getProject())) {
                    return provider.getSelectedAndroidDebuggerState(config);
                }
            }
        }

        return null;
    }

    protected static void detachXDebugSession(@NotNull XDebugSession debugSession) {
        RunContentDescriptor descriptor = debugSession.getRunContentDescriptor();
        ProcessHandler processHandler = descriptor.getProcessHandler();
        if (processHandler != null) {
            processHandler.detachProcess();
        }

    }

    protected boolean hasExistingSession(@NotNull Project project, @NotNull Client client) {
        XDebugSession nativeDebugSession = findNativeDebugSession(project, client);
        return nativeDebugSession != null && activateDebugSessionWindow(project, nativeDebugSession.getRunContentDescriptor());
    }

    @Nullable
    protected static XDebugSession findNativeDebugSession(@NotNull Project project, @NotNull final Client client) {
        return findXDebugSession(project, (debugProcess) -> {
            if (!(debugProcess instanceof AndroidNativeAppDebugProcess)) {
                return false;
            } else {
                AndroidNativeAppDebugProcess nativeDebugProcess = (AndroidNativeAppDebugProcess)debugProcess;
                return nativeDebugProcess.getClient().getClientData().getPid() == client.getClientData().getPid();
            }
        });
    }

    @Nullable
    protected static XDebugSession findXDebugSession(@NotNull Project project, @NotNull NotNullFunction<XDebugProcess, Boolean> debugProcessFilter) {
        XDebugSession[] var2 = XDebuggerManager.getInstance(project).getDebugSessions();

        for (XDebugSession debugSession : var2) {
            XDebugProcess debugProcess = debugSession.getDebugProcess();
            if ((Boolean) debugProcessFilter.fun(debugProcess)) {
                return debugSession;
            }
        }

        return null;
    }

    @NotNull
    public AndroidNativeAppDebugProcess newAndroidNativeAppDebugProcess(@NotNull String sessionId, @NotNull RunParameters parameters, @NotNull XDebugSession session, @NotNull TextConsoleBuilder consoleBuilder, @NotNull ConsolePrinter printer, @NotNull SessionStarter sessionStarter, @NotNull Client client, @Nullable JdwpConnector jdwpConnector, @NotNull ProgressReporter progressReporter, boolean detachOnStop, @NotNull Abi lldbServerAbi) throws ExecutionException {
        return new AndroidNativeAppDebugProcess(sessionId, parameters, session, consoleBuilder, printer, sessionStarter, jdwpConnector, client, progressReporter, detachOnStop, lldbServerAbi);
    }

    @NotNull
    protected static String prepareSetSettingsCommand(@NotNull String... options) {
        return "settings set " + StringUtil.join(options, " ");
    }

    @NotNull
    public List<String> getStartupCommands(@NotNull AndroidFacet facet, @Nullable Client client, @NotNull ProcessHandlerConsolePrinter printer, @NotNull Abi targetAbi) {
        List<String> cmds = new ArrayList<>();
        Map<String, String> sourceMap = AndroidNativeAppDebugProcess.getSourceMap(facet);
        if (!sourceMap.isEmpty()) {
            List<String> arguments = new ArrayList<>(sourceMap.size() * 2);

            for (Entry<String, String> entry : sourceMap.entrySet()) {
                arguments.add("\"" + (String) entry.getKey() + "\"");
                arguments.add("\"" + (String) entry.getValue() + "\"");
            }

            String argumentsStr = StringUtil.join(arguments, " ");
            LOG.info("Set target.source-map: " + argumentsStr);
            Collections.addAll(cmds, prepareSetSettingsCommand("target.source-map", argumentsStr));
        }

        Collections.addAll(cmds, prepareSetSettingsCommand("auto-confirm", "true"));
        Collections.addAll(cmds, prepareSetSettingsCommand("plugin.jit-loader.gdb.enable", "off"));
        if (SystemInfo.isWindows) {
            Collections.addAll(cmds, prepareSetSettingsCommand("use-source-cache", "false"));
        }

        if (targetAbi == Abi.ARMEABI || targetAbi == Abi.ARMEABI_V7A || targetAbi == Abi.ARM64_V8A) {
            cmds.add("type format add -f c-string \"signed char *\"");
        }

        if ((Boolean)StudioFlags.ENABLE_LLDB_NATVIS.get()) {
            cmds.add("type category enable jb_formatters");
        }

        return cmds;
    }

    @NotNull
    public List<String> getPostAttachCommands(@NotNull AndroidFacet facet, @Nullable Client client) {
        List<String> cmds = Lists.newArrayList();
        cmds.add(prepareSetSettingsCommand("target.process.thread.step-avoid-regexp", "''"));
        cmds.add("type format add --format boolean jboolean");
        return cmds;
    }

    @NotNull
    private static String getFirstAbsolutePath(@NotNull Collection<VirtualFile> dirs) {
        VirtualFile f = (VirtualFile)ContainerUtil.getFirstItem(dirs);

        assert f != null;

        return f.getPath();
    }
}
