package com.logguardian.runners;

public final class Command {
    private Command() {
    }

    public static final String BEGIN_LINE = "logguardian> ";
    public static final String WELCOME_LINE_1 = "LogGuardian interactive shell";
    public static final String WELCOME_LINE_2 = "Type 'help' for commands, 'exit' to quit.";
    public static final String LIST = "list";
    public static final String TAIL_ALL = "tail-all";
    public static final String TAIL_ONE = "tail-one";
    public static final String HELP = "help";
    public static final String QUIT = "quit";
    public static final String HELP_2 = "--help";
    public static final String H_COMMAND = "-h";
    public static final String UNKNOWN = "unknown";
    public static final String EXIT = "exit";
    public static final String SHELL_COMMAND = "shell";
    public static final String ACKNOWLEDGED = "ack";
    public static final String CLOSED = "closed";
    public static final String SUPPRESSED = "suppress";
    public static final String RESOLVED  = "resolved";
}
