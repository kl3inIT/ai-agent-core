package com.company.crm.ai.tool;

import com.company.crm.ai.report.introspection.AiReportModelDescriptorYamlExporter;
import com.company.crm.ai.report.run.AiReportExecutionService;
import com.company.crm.report.CategoryCashflowRiskReport;
import com.company.crm.report.InvoiceReport;
import com.vn.agent.spi.ToolContributor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * Exposes CRM-specific report tools to the ai-agent add-on via the ToolContributor SPI.
 * The add-on auto-discovers every ToolContributor bean and registers its @Tool methods
 * with Spring AI's tool-callback resolution.
 */
@Component
public class CrmReportToolContributor implements ToolContributor {

    private static final Set<String> ALLOWED_REPORT_CODES = Set.of(
            "client-360-report",
            CategoryCashflowRiskReport.CODE,
            InvoiceReport.CODE
    );

    private final ReportsDiscoveryTool reportsDiscoveryTool;
    private final RunReportTool runReportTool;

    @Autowired
    public CrmReportToolContributor(AiReportModelDescriptorYamlExporter yamlExporter,
                                    AiReportExecutionService executionService) {
        this.reportsDiscoveryTool = new ReportsDiscoveryTool(yamlExporter, ALLOWED_REPORT_CODES);
        this.runReportTool = new RunReportTool(executionService, ALLOWED_REPORT_CODES);
    }

    @Override
    public List<Object> contribute() {
        return List.of(reportsDiscoveryTool, runReportTool);
    }
}
