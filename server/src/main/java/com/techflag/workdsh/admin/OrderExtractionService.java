package com.techflag.workdsh.admin;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderExtractionService {
    public record Candidate(int sheetIndex, String sheetName, int rowNumber, String locator,
                            String customerSku, String customerReference, String customerName,
                            int quantity, String unitPriceText) {}
    public record Preview(String sourceId, String sourceSha256, boolean recognized, boolean truncated,
                          String message, List<Candidate> candidates) {}
    private record Columns(int sku, int reference, int name, int quantity, int unitPrice) {}

    public Preview excel(String sourceId, String sha256, byte[] content) {
        var candidates = new ArrayList<Candidate>();
        var formatter = new DataFormatter(Locale.CHINA);
        boolean recognized = false;
        boolean truncated = false;
        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(content))) {
            for (int sheetIndex = 0; sheetIndex < Math.min(workbook.getNumberOfSheets(), 10); sheetIndex++) {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                int headerRow = -1;
                Columns columns = null;
                for (int rowIndex = 0; rowIndex <= Math.min(sheet.getLastRowNum(), 30); rowIndex++) {
                    columns = columns(sheet.getRow(rowIndex), formatter);
                    if (columns != null) { headerRow = rowIndex; recognized = true; break; }
                }
                if (headerRow < 0) continue;
                for (int rowIndex = headerRow + 1; rowIndex <= Math.min(sheet.getLastRowNum(), 10_000); rowIndex++) {
                    if (candidates.size() == 500) { truncated = true; break; }
                    Row row = sheet.getRow(rowIndex);
                    if (row == null) continue;
                    String name = cell(row, columns.name(), formatter);
                    int quantity = quantity(cell(row, columns.quantity(), formatter));
                    if (name.isBlank() || name.length() > 255 || quantity < 1) continue;
                    String sku = limited(cell(row, columns.sku(), formatter), 160);
                    String reference = limited(cell(row, columns.reference(), formatter), 160);
                    if (sku == null || reference == null) continue;
                    candidates.add(new Candidate(sheetIndex, sheet.getSheetName(), rowIndex + 1,
                            "sheet:" + sheetIndex + ":row:" + (rowIndex + 1), sku, reference, name,
                            quantity, cell(row, columns.unitPrice(), formatter)));
                }
                if (truncated) break;
            }
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Excel file could not be read");
        }
        String message = !recognized ? "未识别到规格/名称与数量表头，请人工录入并核对原件"
                : candidates.isEmpty() ? "识别到表头，但没有可用的订单行，请人工核对原件"
                : truncated ? "只预览前 500 行；其余行需分批处理" : "以下只是表格读取建议，尚未写入订单或匹配库存 SKU";
        return new Preview(sourceId, sha256, recognized, truncated, message, candidates);
    }

    private static Columns columns(Row row, DataFormatter formatter) {
        if (row == null) return null;
        int sku = -1, reference = -1, name = -1, quantity = -1, price = -1;
        for (int index = 0; index < Math.min(row.getLastCellNum(), 40); index++) {
            String header = cell(row, index, formatter).toLowerCase(Locale.ROOT)
                    .replaceAll("[\\s/_\\-（）()：:]", "");
            if (header.isEmpty()) continue;
            if (sku < 0 && (header.equals("客户sku") || header.equals("客户物料编码") || header.equals("客户编码"))) sku = index;
            if (reference < 0 && (header.contains("空调箱编号") || header.contains("设备编号") || header.contains("楼栋编号") || header.equals("客户参考编号"))) reference = index;
            if (name < 0 && (header.contains("规格型号") || header.equals("规格") || header.equals("物料名称") || header.equals("产品名称") || header.equals("品名"))) name = index;
            if (quantity < 0 && (header.equals("数量") || header.equals("订购数量") || header.equals("采购数量"))) quantity = index;
            if (price < 0 && header.equals("单价")) price = index;
        }
        return name < 0 || quantity < 0 ? null : new Columns(sku, reference, name, quantity, price);
    }

    private static String cell(Row row, int index, DataFormatter formatter) {
        if (row == null || index < 0) return "";
        Cell cell = row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        return cell == null ? "" : formatter.formatCellValue(cell).trim();
    }

    private static int quantity(String value) {
        try {
            int result = new BigDecimal(value.replace(",", "")).intValueExact();
            return result > 0 && result <= 1_000_000 ? result : -1;
        } catch (NumberFormatException | ArithmeticException e) {
            return -1;
        }
    }

    private static String limited(String value, int max) { return value.length() <= max ? value : null; }
}
