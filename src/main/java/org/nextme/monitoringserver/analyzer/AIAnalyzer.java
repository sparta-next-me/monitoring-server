package org.nextme.monitoringserver.analyzer;

import java.util.List;
import java.util.Map;

import org.nextme.monitoringserver.dto.ContainerMetrics;
import org.nextme.monitoringserver.dto.NodeMetrics;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class AIAnalyzer {

	private final ChatClient.Builder chatClientBuilder;

	public String analyzeNodeMetrics(
		NodeMetrics currentMetrics,
		List<NodeMetrics> historicalMetrics,
		String alertInfo
	) {
		log.info("Starting AI analysis for node: {}", currentMetrics.getNodeName());

		try {
			String prompt = buildPrompt(currentMetrics, historicalMetrics, alertInfo, null);

			ChatClient chatClient = chatClientBuilder.build();

			String response = chatClient.prompt()
				.user(prompt)
				.call()
				.content();

			log.info("AI analysis completed successfully");
			return response;

		} catch (Exception e) {
			log.error("Failed to analyze metrics with AI", e);
			return "AI 분석 중 오류가 발생했습니다 : " + e.getMessage();
		}
	}

	public String analyzeNodeWithContainers(
		NodeMetrics currentMetrics,
		List<NodeMetrics> historicalMetrics,
		Map<String, ContainerMetrics> containerMetrics,
		String alertInfo
	) {
		log.info("Starting comprehensive AI analysis for node: {} with {} containers",
			currentMetrics.getNodeName(), containerMetrics.size());

		try {
			String prompt = buildPrompt(currentMetrics, historicalMetrics, alertInfo, containerMetrics);

			log.info("AI Prompt (first 500 chars):\n{}",
				prompt.length() > 500 ? prompt.substring(0, 500) + "..." : prompt);

			ChatClient chatClient = chatClientBuilder.build();

			String response = chatClient.prompt()
				.user(prompt)
				.call()
				.content();

			log.info("AI analysis completed successfully");
			return response;

		} catch (Exception e) {
			log.error("Failed to analyze metrics with AI", e);
			return "AI 분석 중 오류가 발생했습니다 : " + e.getMessage();
		}
	}

	private String buildPrompt(
		NodeMetrics current,
		List<NodeMetrics> history,
		String alertInfo,
		Map<String, ContainerMetrics> containerMetrics
	) {
		StringBuilder sb = new StringBuilder();
		sb.append("### 노드 모니터링 이상 감지 ###\n\n");
		sb.append("**알림 정보 :**\n");
		sb.append(alertInfo).append("\n\n");

		sb.append("**노드 전체 상태 :**\n");
		sb.append(String.format("- 노드 : %s\n", current.getNodeName()));
		sb.append(String.format("- CPU 사용률 : %.2f%%\n", current.getCpuUsage()));
		sb.append(String.format("- 메모리 사용률 : %.2f%%\n", current.getMemoryUsagePercent()));
		sb.append(String.format("- 디스크 사용률 : %.2f%%\n\n", current.getDiskUsagePercent()));

		// 컨테이너별 메트릭 추가
		if (containerMetrics != null && !containerMetrics.isEmpty()) {
			sb.append("**노드 내 서비스별 리소스 사용 현황 :**\n");
			containerMetrics.forEach((name, metrics) -> {
				sb.append(String.format("- %s : CPU %.2f%%, 메모리 %.2f MB\n",
					name, metrics.getCpuUsage(), metrics.getMemoryUsageMB()));
			});
			sb.append("\n");
			log.info("Added {} services to AI prompt", containerMetrics.size());
		} else {
			log.warn("Container metrics is null or empty: null={}, empty={}",
				containerMetrics == null, containerMetrics != null && containerMetrics.isEmpty());
		}

		sb.append("**과거 추세 (최근 6시간):**\n");
		for (int i = 0; i < Math.min(history.size(), 6); i++) {
			NodeMetrics h = history.get(i);
			sb.append(String.format("%d시간 전 - CPU : %.2f%%, 메모리 : %.2f%%, 디스크 : %.2f%%\n",
				i + 1, h.getCpuUsage(), h.getMemoryUsagePercent(), h.getDiskUsagePercent()));
		}

		sb.append("\n**분석 요청 :**\n");
		sb.append("1. 현재 상황이 정상 범위를 벗어났는지 판단\n");
		sb.append("2. 과거 추세와 비교하여 급격한 변화가 있는지 확인\n");
		if (containerMetrics != null && !containerMetrics.isEmpty()) {
			sb.append("3. **중요**: 노드 내 서비스별 리소스 사용 현황을 분석하여 리소스를 가장 많이 사용하는 서비스를 특정\n");
			sb.append("4. 특정된 서비스가 문제의 원인인지 판단\n");
		} else {
			sb.append("3. 가능한 원인 추정\n");
		}
		sb.append("5. 예상되는 리스크 (1시간 후 예측)\n");
		sb.append("6. 권장 조치 사항 (문제 서비스가 특정된 경우 해당 서비스에 대한 조치 포함)\n\n");
		sb.append("**응답 형식 :**\n");
		sb.append("다음 형식으로 작성하되, 마크다운 기호(#, *, -, >, 등) 없이 일반 텍스트로 작성하세요:\n\n");
		sb.append("[현재 상황]\n");
		sb.append("메모리/CPU/디스크 사용률 판단\n\n");
		sb.append("[과거 추세]\n");
		sb.append("6시간 동안의 변화 설명\n\n");
		sb.append("[서비스별 분석]\n");
		sb.append("가장 많이 사용하는 서비스와 사용량 명시\n\n");
		sb.append("[예상 리스크]\n");
		sb.append("1시간 후 예측\n\n");
		sb.append("[권장 조치]\n");
		sb.append("구체적인 조치 사항\n");

		return sb.toString();
	}
}
