package com.meshdrop.cli;

import java.util.List;

/**
 * Unit tests for CommandParser verifying command tokenization, argument handling,
 * quote support (both single and double), and whitespace tolerance.
 */
public class CommandParserTest {

    public void runAll() {
        testHelpCommand();
        testStatusCommand();
        testInfoCommand();
        testPeersCommand();
        testConnectionsCommand();
        testDiscoverCommand();
        testClearCommand();
        testExitAndQuit();
        testSendCommandSimple();
        testSendCommandWithSpaces();
        testSendCommandDoubleQuotedMessage();
        testSendCommandSingleQuotedMessage();
        testSendCommandQuotedPeer();
        testPingCommand();
        testEmptyInput();
        testUnknownCommand();
        testMissingArguments();
        testExtraWhitespaceAndTabs();
    }

    private void testHelpCommand() {
        CommandParser parser = new CommandParser();
        Command cmd = parser.parse("help");
        assert "help".equals(cmd.name()) : "Expected 'help', got " + cmd.name();
        assert cmd.argCount() == 0 : "Expected 0 args, got " + cmd.argCount();
        assert !cmd.isEmpty() : "Should not be empty";
    }

    private void testStatusCommand() {
        CommandParser parser = new CommandParser();
        Command cmd = parser.parse("status");
        assert "status".equals(cmd.name()) : "Expected 'status', got " + cmd.name();
        assert cmd.argCount() == 0 : "Expected 0 args";
    }

    private void testInfoCommand() {
        CommandParser parser = new CommandParser();
        Command cmd = parser.parse("info");
        assert "info".equals(cmd.name()) : "Expected 'info', got " + cmd.name();
        assert cmd.argCount() == 0 : "Expected 0 args";
    }

    private void testPeersCommand() {
        CommandParser parser = new CommandParser();
        Command cmd = parser.parse("peers");
        assert "peers".equals(cmd.name()) : "Expected 'peers', got " + cmd.name();
        assert cmd.argCount() == 0 : "Expected 0 args";
    }

    private void testConnectionsCommand() {
        CommandParser parser = new CommandParser();
        Command cmd = parser.parse("connections");
        assert "connections".equals(cmd.name()) : "Expected 'connections', got " + cmd.name();
        assert cmd.argCount() == 0 : "Expected 0 args";
    }

    private void testDiscoverCommand() {
        CommandParser parser = new CommandParser();
        Command cmd = parser.parse("discover");
        assert "discover".equals(cmd.name()) : "Expected 'discover', got " + cmd.name();
        assert cmd.argCount() == 0 : "Expected 0 args";
    }

    private void testClearCommand() {
        CommandParser parser = new CommandParser();
        Command cmd = parser.parse("clear");
        assert "clear".equals(cmd.name()) : "Expected 'clear', got " + cmd.name();
        assert cmd.argCount() == 0 : "Expected 0 args";
    }

    private void testExitAndQuit() {
        CommandParser parser = new CommandParser();
        assert "exit".equals(parser.parse("exit").name());
        assert "quit".equals(parser.parse("quit").name());
    }

    private void testSendCommandSimple() {
        CommandParser parser = new CommandParser();
        Command cmd = parser.parse("send abc hello");
        assert "send".equals(cmd.name()) : "Expected 'send'";
        assert cmd.argCount() == 2 : "Expected 2 args, got " + cmd.argCount();
        assert "abc".equals(cmd.arg(0)) : "Expected peer 'abc', got " + cmd.arg(0);
        assert "hello".equals(cmd.arg(1)) : "Expected message 'hello', got " + cmd.arg(1);
    }

    private void testSendCommandWithSpaces() {
        CommandParser parser = new CommandParser();
        Command cmd = parser.parse("send abc hello this is a message");
        assert "send".equals(cmd.name()) : "Expected 'send'";
        assert cmd.argCount() == 2 : "Expected 2 args, got " + cmd.argCount();
        assert "abc".equals(cmd.arg(0)) : "Expected peer 'abc'";
        assert "hello this is a message".equals(cmd.arg(1)) : "Expected full message with spaces, got " + cmd.arg(1);
    }

    private void testSendCommandDoubleQuotedMessage() {
        CommandParser parser = new CommandParser();
        Command cmd = parser.parse("send abc \"hello world from meshdrop\"");
        assert "send".equals(cmd.name()) : "Expected 'send'";
        assert cmd.argCount() == 2 : "Expected 2 args";
        assert "abc".equals(cmd.arg(0)) : "Expected 'abc'";
        assert "hello world from meshdrop".equals(cmd.arg(1)) : "Expected unquoted message, got " + cmd.arg(1);
    }

    private void testSendCommandSingleQuotedMessage() {
        CommandParser parser = new CommandParser();
        Command cmd = parser.parse("send abc 'hello from single quotes'");
        assert "send".equals(cmd.name()) : "Expected 'send'";
        assert cmd.argCount() == 2 : "Expected 2 args";
        assert "abc".equals(cmd.arg(0)) : "Expected 'abc'";
        assert "hello from single quotes".equals(cmd.arg(1)) : "Expected single unquoted message, got " + cmd.arg(1);
    }

    private void testSendCommandQuotedPeer() {
        CommandParser parser = new CommandParser();
        Command cmd = parser.parse("send \"My Laptop\" \"hello world\"");
        assert "send".equals(cmd.name()) : "Expected 'send'";
        assert cmd.argCount() == 2 : "Expected 2 args";
        assert "My Laptop".equals(cmd.arg(0)) : "Expected 'My Laptop', got " + cmd.arg(0);
        assert "hello world".equals(cmd.arg(1)) : "Expected 'hello world', got " + cmd.arg(1);

        Command cmdSingle = parser.parse("send 'Office PC' 'test single'");
        assert "send".equals(cmdSingle.name());
        assert "Office PC".equals(cmdSingle.arg(0));
        assert "test single".equals(cmdSingle.arg(1));
    }

    private void testPingCommand() {
        CommandParser parser = new CommandParser();
        Command cmd = parser.parse("ping laptop");
        assert "ping".equals(cmd.name());
        assert cmd.argCount() == 1;
        assert "laptop".equals(cmd.arg(0));
    }

    private void testEmptyInput() {
        CommandParser parser = new CommandParser();
        assert parser.parse("").isEmpty() : "Empty string should be empty";
        assert parser.parse("   ").isEmpty() : "Whitespace should be empty";
        assert parser.parse("\t\n  \r").isEmpty() : "Tabs and newlines should be empty";
        assert parser.parse(null).isEmpty() : "Null should be empty";
    }

    private void testUnknownCommand() {
        CommandParser parser = new CommandParser();
        Command cmd = parser.parse("foobar some arg");
        assert "foobar".equals(cmd.name()) : "Expected 'foobar'";
        assert cmd.argCount() == 2 : "Expected 2 args, got " + cmd.argCount();
        assert "some".equals(cmd.arg(0));
        assert "arg".equals(cmd.arg(1));
    }

    private void testMissingArguments() {
        CommandParser parser = new CommandParser();
        Command cmd = parser.parse("send");
        assert "send".equals(cmd.name());
        assert cmd.argCount() == 0 : "Expected 0 args for bare send";

        Command cmd2 = parser.parse("send abc");
        assert "send".equals(cmd2.name());
        assert cmd2.argCount() == 1 : "Expected 1 arg for send abc";
        assert "abc".equals(cmd2.arg(0));

        Command cmd3 = parser.parse("ping");
        assert "ping".equals(cmd3.name());
        assert cmd3.argCount() == 0;
    }

    private void testExtraWhitespaceAndTabs() {
        CommandParser parser = new CommandParser();
        Command cmd = parser.parse("   \t  status  \t ");
        assert "status".equals(cmd.name());
        assert cmd.argCount() == 0;

        Command cmd2 = parser.parse("   send    abc     hello    world   ");
        assert "send".equals(cmd2.name());
        assert cmd2.argCount() == 2;
        assert "abc".equals(cmd2.arg(0));
        assert "hello    world".equals(cmd2.arg(1));
    }
}
