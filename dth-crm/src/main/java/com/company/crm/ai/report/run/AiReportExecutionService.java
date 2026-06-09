package com.company.crm.ai.report.run;

import io.jmix.reports.ReportRepository;
import io.jmix.reports.entity.Report;
import io.jmix.reports.entity.ReportTemplate;
import io.jmix.reports.runner.ReportRunner;
import io.jmix.reports.yarg.reporting.ReportOutputDocument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;

import static io.jmix.reports.entity.ReportOutputType.valueOf;

/**
 * Executes Jmix reports on behalf of AI tools.
 * Returns the report content as text for LLM analysis; persistence to a chat
 * conversation is handled by the ai-agent add-on itself.
 */
@Service
public class AiReportExecutionService {

    private static final Logger log = LoggerFactory.getLogger(AiReportExecutionService.class);

    private final ReportRunner reportRunner;
    private final ReportRepository reportRepository;
    private final ReportContentConverter contentConverter;
    private final AiReportParameterConverter parameterConverter;

    public AiReportExecutionService(ReportRepository reportRepository,
                                    ReportRunner reportRunner,
                                    AiReportParameterConverter parameterConverter,
                                    ReportContentConverter contentConverter) {
        this.reportRepository = reportRepository;
        this.reportRunner = reportRunner;
        this.parameterConverter = parameterConverter;
        this.contentConverter = contentConverter;
    }

    public ReportExecutionResult executeReport(String reportCode,
                                               Map<String, Object> parameters,
                                               String templateCode,
                                               String outputType,
                                               Collection<String> allowedReportCodes) {
        try {
            if (allowedReportCodes == null || !allowedReportCodes.contains(reportCode)) {
                return ReportExecutionResult.failed(reportCode, ReportExecutionErrorCode.ACCESS_DENIED,
                        "Report execution is not allowed for this report code. Ensure it is whitelisted.");
            }

            Report report = reportRepository.getAllReports().stream()
                    .filter(r -> reportCode.equals(r.getCode()))
                    .findFirst()
                    .orElse(null);
            if (report == null) {
                return ReportExecutionResult.failed(reportCode, ReportExecutionErrorCode.REPORT_NOT_FOUND,
                        "Report with code '" + reportCode + "' not found.");
            }
            report = reportRepository.reloadForRunning(report);

            ReportTemplate template = resolveTemplate(report, templateCode);
            if (templateCode != null && template == null) {
                return ReportExecutionResult.failed(reportCode, ReportExecutionErrorCode.TEMPLATE_NOT_FOUND,
                        "Template with code '" + templateCode + "' not found for this report.");
            }
            if (templateCode == null && outputType != null) {
                ReportTemplate matchingOutputTemplate = resolveTemplateByOutputType(report, outputType);
                if (matchingOutputTemplate != null) {
                    template = matchingOutputTemplate;
                }
            }

            String effectiveTemplateCode = template != null ? template.getCode() : null;
            String effectiveOutputType = outputType != null ? outputType
                    : (template != null && template.getReportOutputType() != null
                        ? template.getReportOutputType().toString() : null);

            ReportParameterConversionResult conversionResult =
                    parameterConverter.convertParameters(report.getInputParameters(), parameters);
            if (!conversionResult.success()) {
                if (conversionResult.hasConversionErrors()) {
                    return ReportExecutionResult.parameterConversionError(reportCode, conversionResult.errors());
                }
                return ReportExecutionResult.validationError(reportCode, conversionResult.errors());
            }

            if (outputType != null) {
                try {
                    valueOf(outputType.toUpperCase());
                } catch (IllegalArgumentException e) {
                    return ReportExecutionResult.failed(reportCode, ReportExecutionErrorCode.INVALID_OUTPUT_TYPE,
                            "Output type '" + outputType + "' is not supported.");
                }
            }
            if (outputType != null && !contentConverter.isTextOutput(outputType)) {
                return ReportExecutionResult.failed(reportCode, ReportExecutionErrorCode.BINARY_OUTPUT_NOT_SUPPORTED_YET,
                        "Binary output formats (like PDF, XLSX) are not yet supported for LLM analysis.");
            }

            var runner = reportRunner.byReportEntity(report)
                    .withParams(conversionResult.convertedParameters());
            if (effectiveTemplateCode != null) {
                runner.withTemplateCode(effectiveTemplateCode);
            }
            if (outputType != null) {
                runner.withOutputType(valueOf(outputType.toUpperCase()));
            }

            ReportOutputDocument document = runner.run();

            ReportContentResult convertedContent = contentConverter.convert(document, effectiveOutputType);
            if (convertedContent instanceof ReportContentResult.BinaryUnsupported) {
                return ReportExecutionResult.failed(reportCode, ReportExecutionErrorCode.BINARY_OUTPUT_NOT_SUPPORTED_YET,
                        "Binary output formats (like PDF, XLSX) are not yet supported for LLM analysis.");
            }
            String content = ((ReportContentResult.TextContent) convertedContent).content();

            return ReportExecutionResult.success(reportCode, effectiveTemplateCode, effectiveOutputType, content);

        } catch (Exception e) {
            log.error("Failed to execute report {}", reportCode, e);
            return ReportExecutionResult.failed(reportCode, ReportExecutionErrorCode.EXECUTION_ERROR,
                    "An unexpected error occurred during report execution: " + e.getMessage());
        }
    }

    private ReportTemplate resolveTemplate(Report report, String templateCode) {
        if (templateCode == null) {
            return report.getDefaultTemplate();
        }
        return report.getTemplates().stream()
                .filter(t -> templateCode.equals(t.getCode()))
                .findFirst()
                .orElse(null);
    }

    private ReportTemplate resolveTemplateByOutputType(Report report, String outputType) {
        try {
            var target = valueOf(outputType.toUpperCase());
            return report.getTemplates().stream()
                    .filter(t -> t.getReportOutputType() == target)
                    .findFirst()
                    .orElse(null);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
