package pe.systemjaade.fe;

import io.github.project.openubl.xbuilder.content.catalogs.Catalog6;
import io.github.project.openubl.xbuilder.content.models.common.Cliente;
import io.github.project.openubl.xbuilder.content.models.common.Proveedor;
import io.github.project.openubl.xbuilder.content.models.standard.general.DocumentoVentaDetalle;
import io.github.project.openubl.xbuilder.content.models.standard.general.Invoice;
import io.github.project.openubl.xbuilder.enricher.ContentEnricher;
import io.github.project.openubl.xbuilder.enricher.config.DateProvider;
import io.github.project.openubl.xbuilder.enricher.config.Defaults;
import io.github.project.openubl.xbuilder.renderer.TemplateProducer;
import io.github.project.openubl.xbuilder.signature.CertificateDetails;
import io.github.project.openubl.xbuilder.signature.CertificateDetailsFactory;
import io.github.project.openubl.xbuilder.signature.XMLSigner;
import io.github.project.openubl.xsender.Constants;
import io.github.project.openubl.xsender.camel.StandaloneCamel;
import io.github.project.openubl.xsender.camel.utils.CamelData;
import io.github.project.openubl.xsender.camel.utils.CamelUtils;
import io.github.project.openubl.xsender.company.CompanyCredentials;
import io.github.project.openubl.xsender.company.CompanyURLs;
import io.github.project.openubl.xsender.files.BillServiceFileAnalyzer;
import io.github.project.openubl.xsender.files.BillServiceXMLFileAnalyzer;
import io.github.project.openubl.xsender.files.ZipFile;
import io.github.project.openubl.xsender.models.SunatResponse;
import io.github.project.openubl.xsender.sunat.BillServiceDestination;
import io.quarkus.qute.Template;
import org.apache.camel.CamelContext;
import org.w3c.dom.Document;

import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.LocalDate;

public class Main {
    public static void main(String[] args) {
        Invoice input = Invoice
                .builder()
                .serie("F001")
                .numero(1)
                .proveedor(Proveedor.builder().ruc("12345678912").razonSocial("Softgreen S.A.C.").build())
                .cliente(Cliente.builder()
                        .nombre("Carlos Feria")
                        .numeroDocumentoIdentidad("12121212121")
                        .tipoDocumentoIdentidad(Catalog6.RUC.toString())
                        .build()
                )
                .detalle(DocumentoVentaDetalle.builder()
                        .descripcion("Item1")
                        .cantidad(new BigDecimal("10"))
                        .precio(new BigDecimal("100"))
                        .unidadMedida("KGM")
                        .build()
                )
                .detalle(DocumentoVentaDetalle.builder()
                        .descripcion("Item2")
                        .cantidad(new BigDecimal("10"))
                        .precio(new BigDecimal("100"))
                        .unidadMedida("KGM")
                        .build()
                )
                .build();

        Defaults defaults = Defaults.builder()
                .moneda("PEN")
                .unidadMedida("NIU")
                .icbTasa(new BigDecimal("0.2"))
                .igvTasa(new BigDecimal("0.18"))
                .build();
        DateProvider dateProvider = () -> LocalDate.of(2019, 12, 24);

        ContentEnricher enricher = new ContentEnricher(defaults, dateProvider);
        enricher.enrich(input);

        Template template = TemplateProducer.getInstance().getInvoice();
        String xml = template.data(input).render();

        try {
            String signatureID = "727"; // Your Signature ID

            InputStream ksInputStream = ClassLoader.getSystemClassLoader().getResource("certificates/cert.pfx").openStream();
            CertificateDetails certificateDetails = CertificateDetailsFactory.create(ksInputStream, "12345678");

            X509Certificate certificate = certificateDetails.getX509Certificate();
            PrivateKey privateKey = certificateDetails.getPrivateKey();

            //Document signedXML = XMLSigner.signXML(xml, signatureID, certificate, privateKey);
            XMLSigner.signXML(xml, signatureID, certificate, privateKey);

            //viewXML(signedXML);

            CompanyURLs companyURLs = CompanyURLs.builder()
                    .invoice("https://e-beta.sunat.gob.pe/ol-ti-itcpfegem-beta/billService")
                    .despatch("https://e-beta.sunat.gob.pe/ol-ti-itemision-guia-gem-beta/billService")
                    .perceptionRetention("https://e-beta.sunat.gob.pe/ol-ti-itemision-otroscpe-gem-beta/billService")
                    .build();

            CompanyCredentials credentials = CompanyCredentials.builder()
                    .username("REDACTED")
                    .password("REDACTED")
                    .build();

            Path miXML = Paths.get("/home/nanamochi/12345678959-01-F001-00000001.xml");
            BillServiceFileAnalyzer fileAnalyzer = new BillServiceXMLFileAnalyzer(miXML, companyURLs);

            // Archivo ZIP
            ZipFile zipFile = fileAnalyzer.getZipFile();

            // Configuración para enviar xml y Configuración para consultar ticket
            BillServiceDestination fileDestination = fileAnalyzer.getSendFileDestination();
            //BillServiceDestination ticketDestination = fileAnalyzer.getVerifyTicketDestination();

            CamelContext camelContext = StandaloneCamel.getInstance()
                    .getMainCamel()
                    .getCamelContext();

            CamelData camelData = CamelUtils.getBillServiceCamelData(zipFile, fileDestination, credentials);

            SunatResponse sendFileSunatResponse = camelContext.createProducerTemplate()
                    .requestBodyAndHeaders(
                            Constants.XSENDER_BILL_SERVICE_URI,
                            camelData.getBody(),
                            camelData.getHeaders(),
                            SunatResponse.class
                    );

            System.out.println(sendFileSunatResponse.getSunat().getTicket());
        } catch (Exception ex) {
            System.err.println(ex.getMessage());
            ex.printStackTrace();
        }
    }

    private static void viewXML(Document signedDocument) throws TransformerException, IOException {
        DOMSource source = new DOMSource(signedDocument);
        FileWriter writer = new FileWriter(new File("/home/nanamochi/12345678959-01-F001-00000001.xml"));
        StreamResult resultXml = new StreamResult(writer);

        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = transformerFactory.newTransformer();
        transformer.transform(source, resultXml);

        File file = new File("/home/nanamochi/12345678959-01-F001-00000001.xml");
        System.out.println(file);

    }
}