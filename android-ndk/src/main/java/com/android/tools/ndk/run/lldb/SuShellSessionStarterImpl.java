package com.android.tools.ndk.run.lldb;

import com.android.ddmlib.AdbCommandRejectedException;
import com.android.ddmlib.Client;
import com.android.ddmlib.DdmPreferences;
import com.android.ddmlib.ShellCommandUnresponsiveException;
import com.android.ddmlib.TimeoutException;
import com.android.tools.idea.run.ConsolePrinter;
import com.android.tools.idea.run.ErrorMatchingReceiver;
import com.android.tools.idea.run.tasks.ShellCommandLauncher;
import com.android.tools.idea.run.util.ProcessHandlerLaunchStatus;
import com.android.tools.ndk.run.AndroidNativeDeviceException;
import com.android.tools.ndk.run.ProgressReporter;
import com.android.tools.ndk.run.editor.NativeAndroidDebuggerState;
import com.intellij.execution.ExecutionException;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.ConcurrencyUtil;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class SuShellSessionStarterImpl extends SessionStarter {
    private static final Logger LOG = Logger.getInstance(SuShellSessionStarterImpl.class);

    protected SuShellSessionStarterImpl(@NotNull Client client, @NotNull File localServerFile, @NotNull File localStartScriptFile, @NotNull NativeAndroidDebuggerState debuggerState, @NotNull ProgressReporter progressReporter, @NotNull ProcessHandlerLaunchStatus launchStatus, @NotNull ConsolePrinter printer) throws ExecutionException {
        super(client.getDevice(), joinPaths(Constants.DEVICE_TEMP_PATH, client.getClientData().getClientDescription()), getHelpfullyNamedSocketDirectory(client), localServerFile, localStartScriptFile, debuggerState, progressReporter, launchStatus, printer);
    }

    private static String getHelpfullyNamedSocketDirectory(@NotNull Client client) {
        String dir = client.getClientData().getPackageName() + "-" + client.getClientData().getUserId();
        if (dir.length() >= 70) {
            dir = dir.substring(dir.length() - 70);
        }

        return "/" + dir;
    }

    protected String getClientCommand(@NotNull String command) {
        command = super.getClientCommand(command);
        if(command.contains("sh -c")){
            command = "su 0 " + command;
        }else{
            command = "su 0 sh -c '" + command.replaceAll("'", "'\"\\'\"'") + "'";
        }
        LOG.info("getClientCommand: " + command);
        return command;
    }

    public void internalStartServer() throws ExecutionException {
        try {
            String mkdirOutput = this.executeCommand(this.getClientCommand(String.format("sh -c 'mkdir -p %s; mkdir -p %s'", this.getTargetRootDirectory(), this.getTargetBinDirectory())), 5L, TimeUnit.SECONDS);
            if (!mkdirOutput.isEmpty()) {
                LOG.warn(mkdirOutput);
            }
            // allow adbd magisk unix_stream_socket *
            this.executeCommand(this.getClientCommand("supolicy --live \"permissive adbd\""), DdmPreferences.getTimeOut(), TimeUnit.MILLISECONDS);

            this.copyFileToTargetBinDirectory(this.myServerTempFile.getFilePath());
            this.copyFileToTargetBinDirectory(this.myStartScriptTempFile.getFilePath());
            String startCmd = this.getClientCommand(this.getStartCommandLine());
            LOG.info("Starting LLDB server : " + startCmd);
            this.myPrinter.stdout("Starting LLDB server: " + startCmd);
            ApplicationManager.getApplication().executeOnPooledThread(() -> {
                ConcurrencyUtil.runUnderThreadName("lldb-server executor", () -> {
                    try {
                        String output = this.executeCommand(startCmd, 0L, TimeUnit.DAYS);
                        LOG.info("LLDB server has exited: " + output);
                    } catch (Exception var3) {
                        LOG.warn("LLDB server has failed: ", var3);
                    }

                });
            });
        } catch (Exception var3) {
            throw new ExecutionException(var3);
        }
    }

    @NotNull
    protected String executeCommand(@NotNull String command, long maxTimeToOutputResponse, TimeUnit maxTimeUnits) throws TimeoutException, AdbCommandRejectedException, ShellCommandUnresponsiveException, IOException, AndroidNativeDeviceException {
        ErrorMatchingReceiver receiver = new ErrorMatchingReceiver(this.myLaunchStatus);
        this.myDevice.executeShellCommand(command, receiver, maxTimeToOutputResponse, maxTimeUnits);
        return receiver.getOutput().toString();
    }

    public void copyFileToTargetBinDirectory(@NotNull String tmpDeviceFile) throws IOException {
        String fileName = (new File(tmpDeviceFile)).getName();
        String destFile = joinPaths(this.getTargetBinDirectory(), fileName);
        String copyChmodCommand = this.getClientCommand(String.format("sh -c 'cp %s %s && chmod 700 %s'", tmpDeviceFile, destFile, destFile));
        LOG.info("Copying to app folder: " + tmpDeviceFile + " => " + destFile);
        LOG.info("Command: " + copyChmodCommand);
        if (!ShellCommandLauncher.execute(copyChmodCommand, this.myDevice, this.myLaunchStatus, this.myPrinter, 60L, TimeUnit.SECONDS)) {
            throw new IOException("Command failed: " + copyChmodCommand);
        }
    }

}
