package io.github.dglearner.opsagent.core.agentops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import io.github.dglearner.opsagent.core.catalog.Catalog;
import io.github.dglearner.opsagent.core.incident.IncidentView;
import io.github.dglearner.opsagent.core.incident.Incidents;
import io.github.dglearner.opsagent.core.incident.TimelineEntry;
import io.github.dglearner.opsagent.core.logs.Logs;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationEventPublisher;

@SuppressWarnings("unchecked")
class AnalysesTest {
    private final UUID incidentId = UUID.randomUUID();
    private final Instant arrived = Instant.now().minusSeconds(120);
    private final AnalysisMapper mapper = mock(AnalysisMapper.class);
    private final Incidents incidents = mock(Incidents.class);
    private final IncidentView incident = mock(IncidentView.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final List<AnalysisRecord> rows = new ArrayList<>();
    private Analyses analyses;

    @BeforeAll
    static void metadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "test"),
                AnalysisRecord.class);
    }

    @BeforeEach
    void setup() {
        analyses = new Analyses(mapper, incidents, mock(Catalog.class), mock(Logs.class),
                new AgentProperties(true, Duration.ofMinutes(1), Duration.ofHours(1), Duration.ofMinutes(3), 3,
                        "zh"), events);
        when(incidents.find(incidentId)).thenReturn(Optional.of(incident));
        when(incident.status()).thenReturn("open");
        when(incident.alertCount()).thenReturn(1);
        when(incidents.timeline(incidentId)).thenReturn(List.of(arrival(arrived)));
        when(mapper.selectList(any(Wrapper.class))).thenAnswer(call -> List.copyOf(rows));
        when(mapper.selectById(any(UUID.class))).thenAnswer(call -> rows.stream()
                .filter(r -> r.getId().equals(call.getArgument(0))).findFirst().orElse(null));
        when(mapper.insertPending(any(), any(), anyString(), any(), any())).thenAnswer(call -> {
            if (rows.stream().anyMatch(r -> r.getStatus().equals("pending"))) {
                return 0;
            }
            AnalysisRecord row = row("pending", call.<OffsetDateTime>getArgument(3).toInstant());
            row.setId(call.getArgument(0));
            row.setRequestedBy(call.getArgument(2));
            rows.addFirst(row);
            return 1;
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "completed", "failed", "timed_out"})
    void sameEvidenceReusesTheAttemptEvenAfterFailure(String status) {
        AnalysisRecord previous = row(status, arrived.plusSeconds(1));
        rows.add(previous);
        assertThat(analyses.ensure(incidentId).orElseThrow().analysisId()).isEqualTo(previous.getId());
        verify(mapper, never()).insertPending(any(), any(), anyString(), any(), any());
        verify(events, never()).publishEvent(any(AnalysisRequested.class));
    }

    @Test
    void newAlertArrivalPermitsOneAttemptAndNextEnsureReusesIt() {
        rows.add(row("completed", arrived.minusSeconds(30)));
        var first = analyses.ensure(incidentId).orElseThrow();
        assertThat(first.status()).isEqualTo("pending");
        assertThat(first.requestedBy()).isEqualTo("system");
        assertThat(analyses.ensure(incidentId).orElseThrow().analysisId()).isEqualTo(first.analysisId());
        assertThat(rows).hasSize(2);
        verify(events).publishEvent(any(AnalysisRequested.class));
    }

    @Test
    void olderPendingTaskIsReusedEvenIfItsEvidenceIsStale() {
        var pending = row("pending", arrived.minusSeconds(30));
        rows.add(row("completed", arrived.minusSeconds(60)));
        rows.add(pending);
        assertThat(analyses.ensure(incidentId).orElseThrow().analysisId()).isEqualTo(pending.getId());
        verify(mapper, never()).insertPending(any(), any(), anyString(), any(), any());
    }

    @Test
    void absentEvidenceAndEndedIncidentsDoNotScheduleWork() {
        when(incidents.timeline(incidentId)).thenReturn(List.of());
        assertThat(analyses.ensure(incidentId)).isEmpty();
        when(incidents.timeline(incidentId)).thenReturn(List.of(arrival(arrived)));
        when(incident.status()).thenReturn("closed");
        assertThat(analyses.ensure(incidentId)).isEmpty();
        verify(mapper, never()).insertPending(any(), any(), anyString(), any(), any());
    }

    @Test
    void automaticCapDoesNotPreventAConfirmedManualReanalysis() {
        for (int n = 1; n <= 3; n++) {
            rows.add(row("failed", arrived.minusSeconds(n)));
        }
        assertThat(analyses.ensure(incidentId)).isEmpty();
        var manual = analyses.request(incidentId, Analyses.USER);
        assertThat(manual.status()).isEqualTo("pending");
        assertThat(manual.requestedBy()).isEqualTo("user");
        assertThat(rows).hasSize(4);
    }

    private TimelineEntry arrival(Instant at) {
        return new TimelineEntry(UUID.randomUUID(), "ALERT_ATTACHED", "SYSTEM", null, null, Map.of(), at);
    }

    private AnalysisRecord row(String status, Instant requestedAt) {
        AnalysisRecord row = new AnalysisRecord();
        row.setId(UUID.randomUUID());
        row.setIncidentId(incidentId);
        row.setStatus(status);
        row.setRequestedBy("system");
        row.setRequestedAt(requestedAt.atOffset(ZoneOffset.UTC));
        row.setHypotheses("[]");
        row.setNextSteps("[]");
        row.setRejectedEvidence("[]");
        return row;
    }
}
