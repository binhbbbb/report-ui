package org.vaadin.reports;

import ar.com.fdvs.dj.core.DynamicJasperHelper;
import ar.com.fdvs.dj.core.layout.ClassicLayoutManager;
import ar.com.fdvs.dj.domain.ColumnProperty;
import ar.com.fdvs.dj.domain.DynamicReport;
import ar.com.fdvs.dj.domain.Style;
import ar.com.fdvs.dj.domain.builders.ColumnBuilder;
import ar.com.fdvs.dj.domain.builders.DynamicReportBuilder;
import ar.com.fdvs.dj.domain.builders.FastReportBuilder;
import ar.com.fdvs.dj.domain.entities.columns.AbstractColumn;
import com.vaadin.flow.component.Composite;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.Html;
import com.vaadin.flow.component.dependency.StyleSheet;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.data.binder.BeanPropertySet;
import com.vaadin.flow.data.binder.PropertyDefinition;
import com.vaadin.flow.data.binder.PropertySet;
import com.vaadin.flow.function.SerializableFunction;
import com.vaadin.flow.function.SerializableSupplier;
import com.vaadin.flow.server.StreamResource;
import com.vaadin.flow.server.VaadinSession;
import net.sf.jasperreports.engine.JRAbstractExporter;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.export.*;
import net.sf.jasperreports.engine.export.oasis.JROdtExporter;
import net.sf.jasperreports.engine.export.ooxml.JRDocxExporter;
import net.sf.jasperreports.engine.export.ooxml.JRPptxExporter;
import net.sf.jasperreports.export.*;
import net.sf.jasperreports.j2ee.servlets.ImageServlet;
import net.sf.jasperreports.web.util.WebHtmlResourceHandler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * @author Alejandro Duarte
 */
@StyleSheet("css/report-ui-styles.css")
public class PrintPreviewReport<T> extends Composite<VerticalLayout> {

    public enum Format {
        PDF(() -> new JRPdfExporter(), os -> new SimpleOutputStreamExporterOutput(os)),
        XLS(() -> new JRXlsExporter(), os -> new SimpleOutputStreamExporterOutput(os)),
        DOCX(() -> new JRDocxExporter(), os -> new SimpleOutputStreamExporterOutput(os)),
        PPTX(() -> new JRPptxExporter(), os -> new SimpleOutputStreamExporterOutput(os)),
        RTF(() -> new JRRtfExporter(), os -> new SimpleWriterExporterOutput(os)),
        ODT(() -> new JROdtExporter(), os -> new SimpleOutputStreamExporterOutput(os)),
        CSV(() -> new JRCsvExporter(), os -> new SimpleWriterExporterOutput(os)),
        XML(() -> new JRXmlExporter(), os -> new SimpleXmlExporterOutput(os));

        private final SerializableSupplier<JRAbstractExporter> exporterSupplier;
        private final SerializableFunction<OutputStream, ExporterOutput> exporterOutputFunction;

        Format(SerializableSupplier<JRAbstractExporter> exporterSupplier, SerializableFunction<OutputStream, ExporterOutput> exporterOutputFunction) {
            this.exporterSupplier = exporterSupplier;
            this.exporterOutputFunction = exporterOutputFunction;
        }
    }

    protected VerticalLayout mainLayout = new VerticalLayout();
    protected DynamicReportBuilder reportBuilder;
    protected DynamicReport report;
    protected JasperPrint print;

    private String imageServletPathPattern = "report-image?image={0}";

    public PrintPreviewReport() {
        reportBuilder = buildReportBuilder();
    }

    public PrintPreviewReport(Class<T> type) {
        this();
        PropertySet<T> propertySet = BeanPropertySet.get(type);
        propertySet.getProperties().forEach(this::addColumn);
    }

    public PrintPreviewReport(Class<T> type, String... columnIds) {
        this();
        PropertySet<T> propertySet = BeanPropertySet.get(type);

        for (int i = 0; i < columnIds.length; i++) {
            String columnId = columnIds[i];
            PropertyDefinition<T, ?> propertyDefinition = propertySet.getProperties()
                    .filter(p -> columnId.equals(p.getName()))
                    .findFirst().get();
            addColumn(propertyDefinition);
        }
    }

    @Override
    protected VerticalLayout initContent() {
        mainLayout.setMargin(false);
        mainLayout.addClassName(PrintPreviewReport.class.getSimpleName() + "-mainLayout");
        return mainLayout;
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        super.onDetach(detachEvent);
        VaadinSession.getCurrent().getSession().removeAttribute(ImageServlet.DEFAULT_JASPER_PRINT_SESSION_ATTRIBUTE);
    }

    public void setItems(List<? extends T> items) {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            if (report == null) {
                report = reportBuilder.build();
            }

            print = buildJasperPrint(items, report);
            HtmlExporter exporter = new HtmlExporter();

            SimpleHtmlExporterOutput exporterOutput = new SimpleHtmlExporterOutput(outputStream);
            exporterOutput.setImageHandler(new WebHtmlResourceHandler(imageServletPathPattern));

            exporter.setExporterOutput(exporterOutput);
            exporter.setExporterInput(new SimpleExporterInput(print));
            exporter.exportReport();
            outputStream.flush();

            Html htmlContent = new Html(outputStream.toString(StandardCharsets.UTF_8.name()));
            Div htmlContainer = new Div(htmlContent);
            htmlContainer.addClassName(PrintPreviewReport.class.getSimpleName() + "-htmlContainer");

            mainLayout.removeAll();
            mainLayout.add(htmlContainer);

        } catch (JRException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    public StreamResource getStreamResource(String fileName, SerializableSupplier<List<? extends T>> itemsSupplier, Format format) {
        return getStreamResource(fileName, itemsSupplier, format.exporterSupplier, format.exporterOutputFunction);
    }

    private StreamResource getStreamResource(String fileName, SerializableSupplier<List<? extends T>> itemsSupplier, SerializableSupplier<JRAbstractExporter> exporterSupplier, SerializableFunction<OutputStream, ExporterOutput> exporterOutputFunction) {
        List<? extends T> items = itemsSupplier.get();
        setItems(items);

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
            JRAbstractExporter exporter = exporterSupplier.get();
            exporter.setExporterOutput(exporterOutputFunction.apply(outputStream));
            exporter.setExporterInput(new SimpleExporterInput(print));
            exporter.exportReport();
            outputStream.flush();

            ByteArrayInputStream inputStream = new ByteArrayInputStream(outputStream.toByteArray());
            return new StreamResource(fileName, () -> inputStream);

        } catch (JRException | IOException e) {
            throw new RuntimeException(e);
        }
    }

    public DynamicReportBuilder getReportBuilder() {
        return reportBuilder;
    }

    public String getImageServletPathPattern() {
        return imageServletPathPattern;
    }

    public void setImageServletPathPattern(String imageServletPathPattern) {
        this.imageServletPathPattern = imageServletPathPattern;
    }

    protected AbstractColumn addColumn(PropertyDefinition<T, ?> propertyDefinition) {
        AbstractColumn column = ColumnBuilder.getNew()
                .setColumnProperty(new ColumnProperty(propertyDefinition.getName(), propertyDefinition.getType().getName()))
                .build();

        column.setTitle(propertyDefinition.getCaption());
        reportBuilder.addColumn(column);

        return column;
    }

    protected DynamicReportBuilder buildReportBuilder() {
        return new FastReportBuilder()
                .setUseFullPageWidth(true)
                .setWhenNoData("(no data)", new Style());
    }

    protected JasperPrint buildJasperPrint(List<? extends T> items, DynamicReport report) throws JRException {
        JasperPrint print = DynamicJasperHelper.generateJasperPrint(report, new ClassicLayoutManager(), items);
        VaadinSession.getCurrent().getSession().setAttribute(ImageServlet.DEFAULT_JASPER_PRINT_SESSION_ATTRIBUTE, print);
        return print;
    }

}
