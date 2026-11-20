package ru.itmo.advisor.analysis.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static ru.itmo.advisor.domain.fixtures.IncidentFixtures.host;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import ru.itmo.advisor.domain.fixtures.CascadeFixture;
import ru.itmo.advisor.domain.model.ServiceGraph;

class TagServiceGraphProviderTest {

    @Test
    void buildsDirectedDependenciesAndKeepsIndependentBranchSeparate() {
        var hosts = new ArrayList<>(CascadeFixture.HOSTS);
        hosts.add(host("billing", "ledger"));
        hosts.add(host("ledger"));

        ServiceGraph graph = new TagServiceGraphProvider().build(hosts);

        assertThat(graph.dependencies("gateway")).containsExactly("orders");
        assertThat(graph.dependencies("orders")).containsExactly("database");
        assertThat(graph.dependents("database")).containsExactly("orders");
        assertThat(graph.dependencies("database")).isEmpty();
        assertThat(graph.dependencies("billing")).containsExactly("ledger");
        assertThat(graph.neighbourhood("database", 3)).containsExactlyInAnyOrder("database", "orders", "gateway");
        assertThat(graph.neighbourhood("ledger", 3)).containsExactlyInAnyOrder("ledger", "billing");
    }
}
