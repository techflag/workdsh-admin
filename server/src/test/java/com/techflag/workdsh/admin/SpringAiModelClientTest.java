package com.techflag.workdsh.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpringAiModelClientTest {
    @Test void streamsToolRequestsWithoutExecutingThem() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var key = new AtomicReference<String>();
        var input = new AtomicReference<String>();
        server.createContext("/v1/chat/completions", exchange -> {
            key.set(exchange.getRequestHeaders().getFirst("Authorization"));
            input.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String data = "data: {\"id\":\"chat-test\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"test-model\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"tool_calls\":[{\"index\":0,\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"arguments\":\"{}\"}}]},\"finish_reason\":null}]}\n\ndata: [DONE]\n\n";
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try(var out = exchange.getResponseBody()) { out.write(data.getBytes(StandardCharsets.UTF_8)); }
        });
        server.start();
        try {
            var json = new ObjectMapper();
            var client = new SpringAiModelClient(json);
            String request = "{\"model\":\"test-model\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"test\"}],\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"lookup\",\"parameters\":{\"type\":\"object\",\"properties\":{}}}}]}";
            var chunks = client.stream("http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "supplier-test-key", request.getBytes(StandardCharsets.UTF_8)).collectList().block(java.time.Duration.ofSeconds(10));
            assertNotNull(chunks);
            assertFalse(chunks.isEmpty());
            assertTrue(json.writeValueAsString(chunks).contains("lookup"));
            assertEquals("Bearer supplier-test-key", key.get());
            assertTrue(input.get().contains("lookup"));
        } finally { server.stop(0); }
    }
    @Test void messagesUsesSupplierKeyAndVersion() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var key=new AtomicReference<String>();
        var version=new AtomicReference<String>();
        server.createContext("/v1/messages",exchange->{
            key.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            version.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
            exchange.getRequestBody().readAllBytes();
            byte[] result="{\"id\":\"msg-test\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"test-model\",\"content\":[{\"type\":\"text\",\"text\":\"fixture\"}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,result.length);
            try(var out=exchange.getResponseBody()){out.write(result);}
        });
        server.start();
        try {
            var json=new ObjectMapper();
            var client=new SpringAiModelClient(json);
            byte[] request="{\"model\":\"test-model\",\"stream\":false,\"max_tokens\":16,\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}".getBytes(StandardCharsets.UTF_8);
            var result=client.completeMessages("http://127.0.0.1:"+server.getAddress().getPort()+"/v1","supplier-test-key",null,request);
            assertTrue(json.writeValueAsString(result).contains("fixture"));
            assertEquals("supplier-test-key",key.get());
            assertEquals("2023-06-01",version.get());
        } finally {server.stop(0);}
    }
}
