package org.nextme.monitoringserver.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.nextme.monitoringserver.analyzer.AIAnalyzer;
import org.nextme.monitoringserver.client.PrometheusClient;
import org.nextme.monitoringserver.dto.ContainerMetrics;
import org.nextme.monitoringserver.dto.GrafanaAlert;
import org.nextme.monitoringserver.dto.NodeMetrics;
import org.nextme.monitoringserver.event.MonitoringNotificationEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Grafana Alert Webhook 수신 Controller
 */
@Slf4j
@RestController
@RequestMapping("/v1/monitoring")
@RequiredArgsConstructor
public class GrafanaAlertController {

	private final PrometheusClient prometheusClient;
	private final AIAnalyzer aiAnalyzer;
	private final KafkaTemplate<String, MonitoringNotificationEvent> kafkaTemplate;

	@Value("${notification.slack.user-ids}")
	private List<String> slackUserIds;

	/**
	 * Grafana Alert Webhook 엔드포인트
	 */
	@PostMapping("/alert")
	public ResponseEntity<String> handleAlert(@RequestBody String rawPayload) {
		log.info("Received Grafana alert payload: {}", rawPayload);

		try {
			// JSON 파싱
			com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
			GrafanaAlert alert = mapper.readValue(rawPayload, GrafanaAlert.class);

			log.info("Parsed alert: status={}, alerts count={}",
					alert.getStatus(), alert.getAlerts() != null ? alert.getAlerts().size() : 0);

			// 1. Alert 정보 파싱
			if (alert.getAlerts() == null || alert.getAlerts().isEmpty()) {
				log.warn("No alerts in payload");
				return ResponseEntity.ok("No alerts to process");
			}

			GrafanaAlert.Alert firstAlert = alert.getAlerts().get(0);
			Map<String, String> labels = firstAlert.getLabels();

			log.info("Alert labels: {}", labels);
			log.info("Common labels: {}", alert.getCommonLabels());
			log.info("Group labels: {}", alert.getGroupLabels());

			// commonLabels와 alert labels를 병합 (commonLabels 우선)
			Map<String, String> mergedLabels = new HashMap<>();
			if (labels != null) {
				mergedLabels.putAll(labels);
			}
			if (alert.getCommonLabels() != null) {
				mergedLabels.putAll(alert.getCommonLabels());
			}

			log.info("Merged labels: {}", mergedLabels);
			log.info("Annotations: {}", firstAlert.getAnnotations());
			log.info("Common annotations: {}", alert.getCommonAnnotations());

			String nodeName = extractNodeName(mergedLabels, firstAlert.getAnnotations());
			String alertName = mergedLabels.getOrDefault("alertname", "Unknown");
			String severity = mergedLabels.getOrDefault("severity", "warning");

			log.info("Processing alert: name={}, node={}, severity={}",
					alertName, nodeName, severity);

			// 2. Prometheus에서 과거 메트릭 조회
			List<NodeMetrics> historicalMetrics = prometheusClient.getNodeMetricsHistory(nodeName, 6);

			if (historicalMetrics.isEmpty()) {
				log.warn("No historical metrics found for node: {}", nodeName);
				return ResponseEntity.ok("No metrics data available");
			}

			// 3. 현재 메트릭 (가장 최근 데이터)
			NodeMetrics currentMetrics = historicalMetrics.get(0);

			// 4. 컨테이너별 메트릭 조회
			Map<String, ContainerMetrics> containerMetrics = prometheusClient.getContainerMetrics(nodeName);

			// 5. Alert 정보 요약
			String alertInfo = buildAlertInfo(alertName, labels, firstAlert.getAnnotations());

			// 6. AI 분석 (컨테이너 메트릭 포함)
			String analysis = aiAnalyzer.analyzeNodeWithContainers(
					currentMetrics,
					historicalMetrics,
					containerMetrics,
					alertInfo
			);

			log.info("AI analysis completed");

			// 7. Kafka로 알림 발송
			sendNotification(nodeName, alertName, analysis);

			return ResponseEntity.ok("Alert processed successfully");

		} catch (Exception e) {
			log.error("Failed to process alert", e);
			return ResponseEntity.internalServerError()
					.body("Error: " + e.getMessage());
		}
	}

	/**
	 * 노드 이름 추출 (labels와 annotations 모두 확인)
	 */
	private String extractNodeName(Map<String, String> labels, Map<String, String> annotations) {
		// 1. Annotations에서 노드 정보 확인 (Grafana Alert Rule의 Annotations 섹션에서 설정)
		if (annotations != null) {
			String node = annotations.get("node");
			if (node != null && !node.isEmpty()) {
				return node;
			}
			String nodeName = annotations.get("node_name");
			if (nodeName != null && !nodeName.isEmpty()) {
				return nodeName;
			}
		}

		// 2. Labels에서 노드 정보 확인
		String node = labels.get("node");
		if (node != null && !node.isEmpty()) {
			return node;
		}

		String nodeName = labels.get("node_name");
		if (nodeName != null && !nodeName.isEmpty()) {
			return nodeName;
		}

		// 3. instance 레이블에서 노드 이름 추출
		String instance = labels.getOrDefault("instance", "");

		// 4. job 레이블 사용
		String job = labels.getOrDefault("job", "");

		if (job.contains("node")) {
			return job;
		}

		// instance에서 IP만 추출
		if (instance.contains(":")) {
			return instance.split(":")[0];
		}

		return instance.isEmpty() ? "unknown" : instance;
	}

	/**
	 * Alert 정보 요약
	 */
	private String buildAlertInfo(String alertName, Map<String, String> labels,
								   Map<String, String> annotations) {
		StringBuilder sb = new StringBuilder();

		sb.append("Alert: ").append(alertName).append("\n");
		sb.append("Severity: ").append(labels.getOrDefault("severity", "unknown")).append("\n");

		if (annotations != null && annotations.containsKey("summary")) {
			sb.append("Summary: ").append(annotations.get("summary")).append("\n");
		}

		if (annotations != null && annotations.containsKey("description")) {
			sb.append("Description: ").append(annotations.get("description"));
		}

		return sb.toString();
	}

	/**
	 * Kafka로 알림 발송
	 */
	private void sendNotification(String nodeName, String alertName, String analysis) {
		String message = String.format(
				"🚨 *노드 알림: %s*\n\n" +
						"*Alert:* %s\n\n" +
						"*AI 분석 결과:*\n%s",
				nodeName, alertName, analysis
		);

		MonitoringNotificationEvent event = new MonitoringNotificationEvent(
				slackUserIds,
				message
		);

		kafkaTemplate.send("monitoring.notification", event);
		log.info("Notification sent to Kafka topic: monitoring.notification");
	}

	/**
	 * Health check 엔드포인트
	 */
	@GetMapping("/health")
	public ResponseEntity<String> health() {
		return ResponseEntity.ok("Monitoring server is running");
	}

	/**
	 * 수동 분석 테스트 엔드포인트
	 *
	 * @param nodeName 노드 이름 (예: app-vm)
	 */
	@PostMapping("/analyze")
	public ResponseEntity<String> manualAnalyze(@RequestParam String nodeName) {
		log.info("Manual analysis requested for node: {}", nodeName);

		try {
			// 1. Prometheus에서 메트릭 조회
			List<NodeMetrics> historicalMetrics = prometheusClient.getNodeMetricsHistory(nodeName, 6);

			if (historicalMetrics.isEmpty()) {
				return ResponseEntity.badRequest()
						.body("No metrics found for node: " + nodeName);
			}

			NodeMetrics currentMetrics = historicalMetrics.get(0);

			// 2. 컨테이너별 메트릭 조회
			Map<String, ContainerMetrics> containerMetrics = prometheusClient.getContainerMetrics(nodeName);

			// 3. Alert 정보 (수동 테스트용)
			String alertInfo = String.format(
					"Alert: Manual Test\nSeverity: info\nSummary: Manual analysis requested for %s",
					nodeName
			);

			// 4. AI 분석 (컨테이너 메트릭 포함)
			String analysis = aiAnalyzer.analyzeNodeWithContainers(
					currentMetrics,
					historicalMetrics,
					containerMetrics,
					alertInfo
			);

			// 5. Slack 알림
			sendNotification(nodeName, "Manual Analysis", analysis);

			return ResponseEntity.ok("Analysis completed:\n\n" + analysis);

		} catch (Exception e) {
			log.error("Manual analysis failed", e);
			return ResponseEntity.internalServerError()
					.body("Error: " + e.getMessage());
		}
	}
}
