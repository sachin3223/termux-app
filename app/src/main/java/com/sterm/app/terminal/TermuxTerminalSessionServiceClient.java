package com.sterm.app.terminal;

import android.app.Service;

import androidx.annotation.NonNull;

import com.sterm.app.TermuxService;
import com.sterm.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.sterm.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.sterm.terminal.TerminalSession;
import com.sterm.terminal.TerminalSessionClient;

/** The {@link TerminalSessionClient} implementation that may require a {@link Service} for its interface methods. */
public class TermuxTerminalSessionServiceClient extends TermuxTerminalSessionClientBase {

    private static final String LOG_TAG = "TermuxTerminalSessionServiceClient";

    private final TermuxService mService;

    public TermuxTerminalSessionServiceClient(TermuxService service) {
        this.mService = service;
    }

    @Override
    public void setTerminalShellPid(@NonNull TerminalSession terminalSession, int pid) {
        TermuxSession termuxSession = mService.getTermuxSessionForTerminalSession(terminalSession);
        if (termuxSession != null)
            termuxSession.getExecutionCommand().mPid = pid;
    }

    @Override
    public void onAndroidBridgeCommand(@NonNull TerminalSession session, String command) {
        AndroidBridgeHandler.handleCommand(mService, session, command);
    }

}
