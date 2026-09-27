package com.techflag.workdsh.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:enterprise-flow;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "workdsh.bootstrap.admin-email=admin@example.test",
        "workdsh.bootstrap.admin-password=SafeBootstrapPassword123!"
})
@AutoConfigureMockMvc
class EnterpriseFlowTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void twoPeopleReviewWithRevocationAndCrossOrderIsolation() throws Exception {
        String admin = login("admin@example.test", "SafeBootstrapPassword123!");
        JsonNode sellerCreated = call(post("/api/admin/members").header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"seller@example.test\",\"displayName\":\"销售\",\"role\":\"MEMBER\"}"), 201);
        JsonNode reviewerCreated = call(post("/api/admin/members").header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"reviewer@example.test\",\"displayName\":\"复核员\",\"role\":\"MEMBER\"}"), 201);
        String sellerId = sellerCreated.path("member").path("id").asText();
        String reviewerId = reviewerCreated.path("member").path("id").asText();
        String seller = login("seller@example.test", sellerCreated.path("temporaryPassword").asText());
        String reviewer = login("reviewer@example.test", reviewerCreated.path("temporaryPassword").asText());

        call(get("/api/orders").header("Authorization", bearer(seller)), 403);
        changePassword(seller, sellerCreated.path("temporaryPassword").asText(), "SellerNewPassword123!");
        changePassword(reviewer, reviewerCreated.path("temporaryPassword").asText(), "ReviewerNewPassword123!");
        seller = login("seller@example.test", "SellerNewPassword123!");
        reviewer = login("reviewer@example.test", "ReviewerNewPassword123!");

        String runtimeToken = call(post("/api/admin/members/" + reviewerId + "/runtime-credential")
                .header("Authorization", bearer(admin)), 200).path("runtimeToken").asText();
        String sellerRuntimeToken = call(post("/api/admin/members/" + sellerId + "/runtime-credential")
                .header("Authorization", bearer(admin)), 200).path("runtimeToken").asText();
        JsonNode runtimeIdentity = call(get("/api/internal/runtime/identity")
                .header("Authorization", "Runtime " + runtimeToken), 200);
        assertEquals(7, runtimeIdentity.size());
        assertEquals(1, runtimeIdentity.path("contractVersion").asInt());
        assertEquals(reviewerId, runtimeIdentity.path("principalId").asText());
        assertFalse(runtimeIdentity.path("organizationId").asText().isBlank());
        assertFalse(runtimeIdentity.path("organizationName").asText().isBlank());
        assertEquals("MEMBER", runtimeIdentity.path("role").asText());
        assertTrue(runtimeIdentity.path("membershipRevision").asInt() > 0);
        assertTrue(runtimeIdentity.path("active").asBoolean());
        call(get("/api/admin/members").header("Authorization", "Runtime " + runtimeToken), 401);
        call(get("/api/admin/orders").header("Authorization", "Runtime " + runtimeToken), 401);

        call(post("/api/admin/members").header("Authorization", bearer(seller))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"other@example.test\",\"displayName\":\"其他\",\"role\":\"MEMBER\"}"), 403);

        JsonNode order = call(post("/api/orders").header("Authorization", "Runtime " + sellerRuntimeToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerName\":\"美埃\",\"sourceType\":\"EXCEL\",\"sourceName\":\"order.xlsx\",\"lines\":[{\"customerSku\":\"客户-A\",\"customerName\":\"过滤器\",\"quantity\":2}]}"), 201);
        String orderId = order.path("id").asText();
        assertEquals(sellerId, order.path("creatorId").asText());
        call(post("/api/orders/" + orderId + "/submit-review").header("Authorization", bearer(seller))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reviewerId\":\"" + reviewerId + "\",\"expectedRevision\":1}"), 409);
        MockMultipartFile sourceFile = new MockMultipartFile("file", "order.pdf", "application/pdf", "%PDF-1.4 test order".getBytes());
        JsonNode source = call(multipart("/api/orders/" + orderId + "/sources").file(sourceFile)
                .param("expectedRevision", "1")
                .header("Authorization", bearer(seller)), 201);
        String sourceId = source.path("id").asText();
        assertEquals("PDF", source.path("sourceType").asText());
        call(multipart("/api/orders/" + orderId + "/sources")
                .file(new MockMultipartFile("file", "fake.pdf", "application/pdf", "not a pdf".getBytes()))
                .param("expectedRevision", "2")
                .header("Authorization", bearer(seller)), 400);
        call(multipart("/api/orders/" + orderId + "/sources").file(sourceFile)
                .param("expectedRevision", "1").header("Authorization", bearer(seller)), 409);
        assertEquals(1, call(get("/api/orders/" + orderId + "/sources")
                .header("Authorization", bearer(seller)), 200).size());
        call(get("/api/orders/" + orderId + "/sources/" + sourceId + "/download")
                .header("Authorization", bearer(reviewer)), 404);
        call(get("/api/orders/" + orderId).header("Authorization", "Runtime " + runtimeToken), 404);
        call(get("/api/orders/" + orderId).header("Authorization", bearer(reviewer)), 404);
        call(get("/api/orders/" + orderId).header("Authorization", bearer(admin)), 404);

        order = call(post("/api/orders/" + orderId + "/submit-review")
                .header("Authorization", "Runtime " + sellerRuntimeToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reviewerId\":\"" + reviewerId + "\",\"expectedRevision\":2}"), 200);
        assertEquals("IN_REVIEW", order.path("status").asText());
        call(multipart("/api/orders/" + orderId + "/sources").file(sourceFile)
                .param("expectedRevision", "2")
                .header("Authorization", bearer(seller)), 403);
        call(multipart("/api/orders/" + orderId + "/sources").file(sourceFile)
                .param("expectedRevision", "2")
                .header("Authorization", bearer(reviewer)), 403);
        call(get("/api/orders/" + orderId + "/sources/" + sourceId + "/download")
                .header("Authorization", bearer(admin)), 404);
        assertEquals("%PDF-1.4 test order", mvc.perform(get("/api/orders/" + orderId + "/sources/" + sourceId + "/download")
                .header("Authorization", bearer(reviewer))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(1, call(get("/api/reviews/inbox").header("Authorization", bearer(reviewer)), 200).size());
        assertEquals(1, call(get("/api/reviews/inbox").header("Authorization", "Runtime " + runtimeToken), 200).size());
        call(post("/api/orders/" + orderId + "/review").header("Authorization", "Runtime " + runtimeToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVED\",\"expectedRevision\":3}"), 409);
        call(post("/api/orders/" + orderId + "/review").header("Authorization", bearer(seller))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVED\",\"expectedRevision\":3}"), 403);

        order = call(post("/api/orders/" + orderId + "/review").header("Authorization", "Runtime " + runtimeToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"CHANGES_REQUESTED\",\"comment\":\"请确认库存 SKU\",\"expectedRevision\":3}"), 200);
        assertEquals("CHANGES_REQUESTED", order.path("status").asText());
        assertEquals(0, call(get("/api/reviews/inbox").header("Authorization", bearer(reviewer)), 200).size());
        assertEquals("请确认库存 SKU", call(get("/api/orders/" + orderId + "/events")
                .header("Authorization", "Runtime " + sellerRuntimeToken), 200).get(1).path("comment").asText());
        call(get("/api/orders/" + orderId + "/events").header("Authorization", bearer(admin)), 404);
        String lineId = order.path("lines").get(0).path("id").asText();
        call(patch("/api/orders/" + orderId + "/lines/" + lineId + "/match")
                .header("Authorization", "Runtime " + sellerRuntimeToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"internalSku\":\"库存-A\",\"expectedRevision\":3}"), 409);
        order = call(patch("/api/orders/" + orderId + "/lines/" + lineId + "/match")
                .header("Authorization", "Runtime " + sellerRuntimeToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"internalSku\":\"库存-A\",\"expectedRevision\":4}"), 200);
        assertEquals(5, order.path("revision").asInt());
        order = call(post("/api/orders/" + orderId + "/submit-review")
                .header("Authorization", "Runtime " + sellerRuntimeToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reviewerId\":\"" + reviewerId + "\",\"expectedRevision\":5}"), 200);
        order = call(post("/api/orders/" + orderId + "/review").header("Authorization", "Runtime " + runtimeToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"decision\":\"APPROVED\",\"expectedRevision\":6}"), 200);
        assertEquals("APPROVED", order.path("status").asText());
        assertEquals(4, call(get("/api/orders/" + orderId + "/events")
                .header("Authorization", bearer(seller)), 200).size());

        JsonNode secondOrder = call(post("/api/orders").header("Authorization", bearer(seller))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerName\":\"再次协作\",\"sourceType\":\"PDF\",\"sourceName\":\"second.pdf\",\"lines\":[{\"customerReference\":\"1#AHU-1F-5\",\"customerName\":\"板式 G4 滤网\",\"quantity\":1}]}"), 201);
        String secondId = secondOrder.path("id").asText();
        assertEquals("", secondOrder.path("lines").get(0).path("customerSku").asText());
        assertEquals("1#AHU-1F-5", secondOrder.path("lines").get(0).path("customerReference").asText());
        call(multipart("/api/orders/" + secondId + "/sources").file(sourceFile)
                .param("expectedRevision", "1")
                .header("Authorization", bearer(seller)), 201);
        String viewerGrant = "{\"memberId\":\"" + reviewerId + "\",\"accessRole\":\"VIEWER\"}";
        call(post("/api/admin/orders/" + secondId + "/access").header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content(viewerGrant), 204);
        call(post("/api/admin/orders/" + secondId + "/access").header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content(viewerGrant), 204);
        assertEquals(1, call(get("/api/orders").header("Authorization", bearer(reviewer)), 200)
                .findValuesAsText("id").stream().filter(secondId::equals).count());
        call(post("/api/orders/" + secondId + "/submit-review").header("Authorization", bearer(seller))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reviewerId\":\"" + reviewerId + "\",\"expectedRevision\":2}"), 200);
        assertEquals(1, call(get("/api/orders").header("Authorization", bearer(reviewer)), 200)
                .findValuesAsText("id").stream().filter(secondId::equals).count());

        JsonNode excelOrder = call(post("/api/orders").header("Authorization", bearer(seller))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerName\":\"表格导入测试\",\"sourceType\":\"EXCEL\",\"sourceName\":\"采购清单\",\"lines\":[]}"), 201);
        String excelId = excelOrder.path("id").asText();
        assertEquals(0, excelOrder.path("lines").size());
        call(post("/api/orders/" + excelId + "/submit-review").header("Authorization", bearer(seller))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reviewerId\":\"" + reviewerId + "\",\"expectedRevision\":1}"), 409);
        JsonNode excelSource = call(multipart("/api/orders/" + excelId + "/sources")
                .file(new MockMultipartFile("file", "sample.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", sampleWorkbook()))
                .param("expectedRevision", "1").header("Authorization", bearer(seller)), 201);
        String excelSourceId = excelSource.path("id").asText();
        call(get("/api/orders/" + excelId + "/sources/" + excelSourceId + "/preview")
                .header("Authorization", bearer(reviewer)), 404);
        JsonNode preview = call(get("/api/orders/" + excelId + "/sources/" + excelSourceId + "/preview")
                .header("Authorization", bearer(seller)), 200);
        assertTrue(preview.path("recognized").asBoolean());
        assertEquals(2, preview.path("candidates").size());
        assertEquals("", preview.path("candidates").get(0).path("customerSku").asText());
        assertEquals("1#AHU-1F-5", preview.path("candidates").get(0).path("customerReference").asText());
        assertEquals("清单统计", preview.path("candidates").get(0).path("sheetName").asText());
        String locator = preview.path("candidates").get(0).path("locator").asText();
        String importBody = json.writeValueAsString(java.util.Map.of("sourceId", excelSourceId, "locator", locator, "expectedRevision", 2));
        JsonNode imported = call(post("/api/orders/" + excelId + "/lines/import")
                .header("Authorization", bearer(seller)).contentType(MediaType.APPLICATION_JSON).content(importBody), 200);
        assertEquals(excelSourceId, imported.path("lines").get(0).path("sourceId").asText());
        assertEquals(locator, imported.path("lines").get(0).path("sourceLocator").asText());
        call(post("/api/orders/" + excelId + "/lines/import")
                .header("Authorization", bearer(seller)).contentType(MediaType.APPLICATION_JSON).content(importBody), 409);
        call(post("/api/orders/" + excelId + "/lines/import")
                .header("Authorization", bearer(reviewer)).contentType(MediaType.APPLICATION_JSON).content(importBody), 404);

        call(patch("/api/admin/members/" + reviewerId).header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"active\":false,\"expectedRevision\":2}"), 200);
        call(get("/api/orders/" + orderId).header("Authorization", bearer(reviewer)), 401);
        call(get("/api/internal/runtime/identity").header("Authorization", "Runtime " + runtimeToken), 401);
        assertTrue(call(get("/api/admin/audit").header("Authorization", bearer(admin)), 200).size() >= 5);
    }

    private void changePassword(String token, String oldPassword, String newPassword) throws Exception {
        call(post("/api/auth/change-password").header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("currentPassword", oldPassword, "newPassword", newPassword))), 204);
    }

    private String login(String email, String password) throws Exception {
        return call(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("email", email, "password", password))), 200).path("token").asText();
    }

    private String bearer(String token) { return "Bearer " + token; }

    private byte[] sampleWorkbook() throws Exception {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("清单统计");
            var header = sheet.createRow(1);
            header.createCell(1).setCellValue("楼栋-空调箱编号");
            header.createCell(2).setCellValue("规格/型号");
            header.createCell(3).setCellValue("数量");
            header.createCell(4).setCellValue("单价");
            var first = sheet.createRow(2);
            first.createCell(1).setCellValue("1#AHU-1F-5");
            first.createCell(2).setCellValue("12x12 板式 G4，厚 46mm");
            first.createCell(3).setCellValue(2);
            first.createCell(4).setCellValue(49);
            var second = sheet.createRow(3);
            second.createCell(1).setCellValue("1#AHU-2F-1");
            second.createCell(2).setCellValue("24x24 板式 G4，厚 46mm");
            second.createCell(3).setCellValue(1);
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private JsonNode call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, int statusCode) throws Exception {
        MvcResult result = mvc.perform(request).andExpect(status().is(statusCode)).andReturn();
        String body = result.getResponse().getContentAsString();
        return body.isBlank() ? json.nullNode() : json.readTree(body);
    }
}
