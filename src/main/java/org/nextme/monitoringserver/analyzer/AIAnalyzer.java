package org.nextme.monitoringserver.analyzer;

import java.util.List;

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
			String prompt = buildPrompt(currentMetrics, historicalMetrics, alertInfo);

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
		String alertInfo
	) {
		StringBuilder sb = new StringBuilder();
		sb.append("### 노드 모니터링 이상 감지 ###\n\n");
		sb.append("**알림 정보 :**\n");
		sb.append(alertInfo).append("\n\n");

		sb.append("**현재 상태 :**\n");
		sb.append(String.format("- 노드 : %s\n", current.getNodeName()));
		sb.append(String.format("- CPU 사용률 : %.2f%%\n", current.getCpuUsage()));
		sb.append(String.format("- 메모리 사용률 : %.2f%%\n", current.getMemoryUsagePercent()));
		sb.append(String.format("- 디스크 사용률 : %.2f%%\n\n", current.getDiskUsagePercent()));

		sb.append("**과거 추세 (최근 6시간):**\n");
		for (int i = 0; i < Math.min(history.size(), 6); i++) {
			NodeMetrics h = history.get(i);
			sb.append(String.format("%d시간 전 - CPU : %.2f%%, 메모리 : %.2f%%, 디스크 : %.2f%%\n",
				i + 1, h.getCpuUsage(), h.getMemoryUsagePercent(), h.getDiskUsagePercent()));
		}

		sb.append("\n**분석 요청 :**\n");
		sb.append("1. 현재 상황이 정상 범위를 벗어났는지 판단\n");
		sb.append("2. 과거 추세와 비교하여 급격한 변화가 있는지 확인\n");
		sb.append("3. 가능한 원인 추정\n");
		sb.append("4. 예상되는 리스크 (1시간 후 예측)\n");
		sb.append("5. 권장 조치 사항\n\n");
		sb.append("**응답 형식 :**\n");
		sb.append("간결하고 명확하게 5줄 이내로 요약해주세요.\n");

		return sb.toString();
	}
}
