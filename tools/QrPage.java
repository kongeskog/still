import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.QRCodeWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Writes a self-refreshing HTML page showing one QR code. Usage: QrPage <text> <out.html> */
public class QrPage {
    public static void main(String[] args) throws Exception {
        var matrix = new QRCodeWriter().encode(args[0], BarcodeFormat.QR_CODE, 0, 0, Map.of(EncodeHintType.MARGIN, 4));
        var svg = new StringBuilder();
        int n = matrix.getWidth();
        svg.append("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 ").append(n).append(' ').append(n)
           .append("' shape-rendering='crispEdges'><rect width='100%' height='100%' fill='#fff'/>");
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++)
            if (matrix.get(x, y)) svg.append("<rect x='").append(x).append("' y='").append(y).append("' width='1' height='1'/>");
        svg.append("</svg>");
        var html = "<!doctype html><meta charset='utf-8'><meta http-equiv='refresh' content='3'><title>Still link code</title>"
            + "<body style='margin:0;background:#fff;font:18px sans-serif;text-align:center'>"
            + "<div style='width:min(80vh,80vw);margin:2vh auto'>" + svg + "</div>"
            + "<p>In Signal: Settings, Linked devices, Link a new device, then scan.</p></body>";
        Files.writeString(Path.of(args[1]), html);
    }
}
