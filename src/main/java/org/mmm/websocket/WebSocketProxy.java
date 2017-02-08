package org.mmm.websocket;

import org.apache.commons.cli.*;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.glassfish.tyrus.ext.client.java8.SessionBuilder;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.websocket.*;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * Created by mmm on 2/7/17.
 */
public class WebSocketProxy extends AbstractHandler implements MessageHandler.Whole<String> {

    private Session session;
    private Consumer<String> output;
    private final ArrayBlockingQueue<String> queue = new ArrayBlockingQueue<>(1);

    public WebSocketProxy(boolean verbose) {
        if (verbose) {
            output = System.out::println;
        } else {
            output = s -> {};
        }
    }

    private interface CmdOptions {
        String PORT = "p";
        String URL = "u";
        String HEADER = "h";
        String VERBOSE = "v";
    }

    public void startProxy(URI wsUrl, Integer port, String[] headers) throws Exception {
        ClientEndpointConfig clientEndpointConfig = ClientEndpointConfig.Builder
                .create()
                .configurator(new WsProxyConfigurator(headers))
                .build();

        Server server = new Server(port);
        server.setHandler(this);
        server.start();

        session = new SessionBuilder().uri(wsUrl)
                .clientEndpointConfig(clientEndpointConfig)
                .onOpen(this::onOpen)
                .onError(this::onError)
                .onClose(this::onClose)
                .messageHandler(String.class, this::onMessage)
                .connect();

        server.join();
    }

    @Override
    public void onMessage(String s) {
        try {
            output.accept("Websocket: " + s);
            queue.put(s);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void handle(String s, Request request, HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) throws IOException, ServletException {
        try {
            String message = request.getReader()
                    .lines()
                    .collect(Collectors.joining(System.lineSeparator()));
            message = URLDecoder.decode(message, "UTF-8");
            output.accept("HTTP: " + message);
            session.getBasicRemote().sendText(message);

            String response = queue.take();
            httpServletResponse.getOutputStream()
                    .write(response.getBytes("UTF-8"));
            request.setHandled(true);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    void onOpen(Session session, EndpointConfig config) {
         output.accept("Websocket opened");
    }

    void onError(Session session, Throwable error) {
        error.printStackTrace();
    }

    void onClose(Session session, CloseReason close) {
        output.accept("Websocket closed");
    }

    private static Options prepareOptions() {
        Options options = new Options();
        options.addOption(Option.builder(CmdOptions.PORT).longOpt("port").numberOfArgs(1).required().build());
        options.addOption(Option.builder(CmdOptions.URL).longOpt("url").numberOfArgs(1).desc("websocket url").required().build());
        options.addOption(Option.builder(CmdOptions.VERBOSE).longOpt("verbose").optionalArg(true).build());
        options.addOption(Option.builder(CmdOptions.HEADER).longOpt("header").numberOfArgs(1).desc("header, key: value").optionalArg(true).build());
        return options;
    }

    private static final class WsProxyConfigurator extends ClientEndpointConfig.Configurator {

        private final Map<String, List<String>> headers = new HashMap<>();

        WsProxyConfigurator(String[] headers) {
            if (headers != null) {
                for (String header : headers) {
                    String[] split = header.split(": ");
                    if (split.length != 2) {
                        throw new IllegalArgumentException("Cannot parse " + header);
                    }
                    this.headers.put(split[0], Arrays.asList(split[1]));
                }
            }
        }

        @Override
        public void beforeRequest(Map<String, List<String>> headers) {
            headers.putAll(this.headers);
            super.beforeRequest(headers);
        }
    }

    public static void main(String[] args) throws Exception {
        Options options = prepareOptions();
        try {
            DefaultParser parser = new DefaultParser();
            CommandLine commandLine = parser.parse(options, args);

            String[] headers = commandLine.getOptionValues(CmdOptions.HEADER);
            Integer port = Integer.parseInt(commandLine.getOptionValue(CmdOptions.PORT));
            URI wsUrl = new URI(commandLine.getOptionValue(CmdOptions.URL));

            WebSocketProxy p = new WebSocketProxy(commandLine.hasOption(CmdOptions.VERBOSE));
            p.startProxy(wsUrl, port, headers);
        } catch (ParseException e) {
            HelpFormatter formatter = new HelpFormatter();
            formatter.printHelp( "WebSocketProxy", options );
        }
    }
}