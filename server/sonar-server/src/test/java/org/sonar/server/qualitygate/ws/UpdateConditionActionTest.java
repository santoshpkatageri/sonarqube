/*
 * SonarQube
 * Copyright (C) 2009-2017 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.qualitygate.ws;

import javax.annotation.Nullable;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.sonar.api.server.ws.WebService;
import org.sonar.api.utils.System2;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.DbTester;
import org.sonar.db.metric.MetricDto;
import org.sonar.db.organization.OrganizationDto;
import org.sonar.db.qualitygate.QualityGateConditionDto;
import org.sonar.db.qualitygate.QualityGateDto;
import org.sonar.server.exceptions.ForbiddenException;
import org.sonar.server.exceptions.NotFoundException;
import org.sonar.server.organization.TestDefaultOrganizationProvider;
import org.sonar.server.qualitygate.QualityGateConditionsUpdater;
import org.sonar.server.tester.UserSessionRule;
import org.sonar.server.ws.TestRequest;
import org.sonar.server.ws.WsActionTester;
import org.sonarqube.ws.Qualitygates.CreateConditionResponse;

import static java.lang.String.format;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.sonar.api.measures.Metric.ValueType.INT;
import static org.sonar.db.permission.OrganizationPermission.ADMINISTER_QUALITY_GATES;
import static org.sonar.server.qualitygate.ws.QualityGatesWsParameters.PARAM_ERROR;
import static org.sonar.server.qualitygate.ws.QualityGatesWsParameters.PARAM_ID;
import static org.sonar.server.qualitygate.ws.QualityGatesWsParameters.PARAM_METRIC;
import static org.sonar.server.qualitygate.ws.QualityGatesWsParameters.PARAM_OPERATOR;
import static org.sonar.server.qualitygate.ws.QualityGatesWsParameters.PARAM_PERIOD;
import static org.sonar.server.qualitygate.ws.QualityGatesWsParameters.PARAM_WARNING;

public class UpdateConditionActionTest {

  @Rule
  public ExpectedException expectedException = ExpectedException.none();

  @Rule
  public UserSessionRule userSession = UserSessionRule.standalone();

  @Rule
  public DbTester db = DbTester.create(System2.INSTANCE);

  private TestDefaultOrganizationProvider defaultOrganizationProvider = TestDefaultOrganizationProvider.from(db);
  private DbClient dbClient = db.getDbClient();
  private DbSession dbSession = db.getSession();
  private UpdateConditionAction underTest = new UpdateConditionAction(dbClient, new QualityGateConditionsUpdater(dbClient),
    new QualityGatesWsSupport(dbClient, userSession, defaultOrganizationProvider));

  private WsActionTester ws = new WsActionTester(underTest);

  @Test
  public void update_warning_condition() throws Exception {
    logInAsQualityGateAdmin();
    QualityGateDto qualityGate = db.qualityGates().insertQualityGate();
    MetricDto metric = insertMetric();
    QualityGateConditionDto condition = db.qualityGates().addCondition(qualityGate, metric,
      c -> c.setOperator("GT").setWarningThreshold(null).setErrorThreshold("80").setPeriod(null));

    executeRequest(condition.getId(), metric.getKey(), "LT", "90", null, null);

    assertCondition(qualityGate, metric, "LT", "90", null, null);
  }

  @Test
  public void update_error_condition() throws Exception {
    logInAsQualityGateAdmin();
    QualityGateDto qualityGate = db.qualityGates().insertQualityGate();
    MetricDto metric = insertMetric();
    QualityGateConditionDto condition = db.qualityGates().addCondition(qualityGate, metric,
      c -> c.setOperator("GT").setWarningThreshold(null).setErrorThreshold("80").setPeriod(null));

    executeRequest(condition.getId(), metric.getKey(), "LT", null, "90", null);

    assertCondition(qualityGate, metric, "LT", null, "90", null);
  }

  @Test
  public void update_condition_over_leak_period() throws Exception {
    logInAsQualityGateAdmin();
    QualityGateDto qualityGate = db.qualityGates().insertQualityGate();
    MetricDto metric = insertMetric();
    QualityGateConditionDto condition = db.qualityGates().addCondition(qualityGate, metric,
      c -> c.setOperator("GT").setWarningThreshold(null).setErrorThreshold("80").setPeriod(null));

    executeRequest(condition.getId(), metric.getKey(), "LT", null, "90", 1);

    assertCondition(qualityGate, metric, "LT", null, "90", 1);
  }

  @Test
  public void test_response() throws Exception {
    logInAsQualityGateAdmin();
    QualityGateDto qualityGate = db.qualityGates().insertQualityGate();
    MetricDto metric = insertMetric();
    QualityGateConditionDto condition = db.qualityGates().addCondition(qualityGate, metric,
      c -> c.setOperator("GT").setWarningThreshold(null).setErrorThreshold("80").setPeriod(null));

    CreateConditionResponse response = executeRequest(condition.getId(), metric.getKey(), "LT", "90", "45", 1);

    assertThat(response.getId()).isEqualTo(condition.getId());
    assertThat(response.getMetric()).isEqualTo(metric.getKey());
    assertThat(response.getOp()).isEqualTo("LT");
    assertThat(response.getWarning()).isEqualTo("90");
    assertThat(response.getError()).isEqualTo("45");
    assertThat(response.getPeriod()).isEqualTo(1);
  }

  @Test
  public void fail_to_update_built_in_quality_gate() {
    logInAsQualityGateAdmin();
    QualityGateDto qualityGate = db.qualityGates().insertQualityGate(qg -> qg.setBuiltIn(true));
    MetricDto metric = insertMetric();
    QualityGateConditionDto condition = db.qualityGates().addCondition(qualityGate, metric);

    expectedException.expect(IllegalArgumentException.class);
    expectedException.expectMessage(format("Operation forbidden for built-in Quality Gate '%s'", qualityGate.getName()));

    executeRequest(condition.getId(), metric.getKey(), "LT", "90", "45", 1);
  }

  @Test
  public void fail_on_unknown_condition() {
    logInAsQualityGateAdmin();
    QualityGateDto qualityGate = db.qualityGates().insertQualityGate();
    MetricDto metric = insertMetric();
    db.qualityGates().addCondition(qualityGate, metric);

    expectedException.expect(NotFoundException.class);
    expectedException.expectMessage("No quality gate condition with id '123'");

    executeRequest(123L, metric.getKey(), "LT", "90", null, null);
  }

  @Test
  public void fail_when_condition_match_unknown_quality_gate() {
    logInAsQualityGateAdmin();
    MetricDto metric = insertMetric();
    QualityGateConditionDto condition = new QualityGateConditionDto().setQualityGateId(123L);
    db.getDbClient().gateConditionDao().insert(condition, dbSession);
    db.commit();

    expectedException.expect(IllegalStateException.class);
    expectedException.expectMessage(format("Condition '%s' is linked to an unknown quality gate '%s'", condition.getId(), 123L));

    executeRequest(condition.getId(), metric.getKey(), "LT", "90", null, null);
  }

  @Test
  public void throw_ForbiddenException_if_not_gate_administrator() throws Exception {
    userSession.logIn();
    QualityGateDto qualityGate = db.qualityGates().insertQualityGate();
    MetricDto metric = insertMetric();
    QualityGateConditionDto condition = db.qualityGates().addCondition(qualityGate, metric);

    expectedException.expect(ForbiddenException.class);
    expectedException.expectMessage("Insufficient privileges");

    executeRequest(condition.getId(), metric.getKey(), "LT", "90", null, null);
  }

  @Test
  public void throw_ForbiddenException_if_not_gate_administrator_of_default_organization() throws Exception {
    // as long as organizations don't support Quality gates, the global permission
    // is defined on the default organization
    OrganizationDto org = db.organizations().insert();
    userSession.logIn().addPermission(ADMINISTER_QUALITY_GATES, org);
    QualityGateDto qualityGate = db.qualityGates().insertQualityGate();
    MetricDto metric = insertMetric();
    QualityGateConditionDto condition = db.qualityGates().addCondition(qualityGate, metric);

    expectedException.expect(ForbiddenException.class);
    expectedException.expectMessage("Insufficient privileges");

    executeRequest(condition.getId(), metric.getKey(), "LT", "90", null, null);
  }

  @Test
  public void test_ws_definition() {
    WebService.Action action = ws.getDef();
    assertThat(action).isNotNull();
    assertThat(action.isInternal()).isFalse();
    assertThat(action.isPost()).isTrue();
    assertThat(action.responseExampleAsString()).isNull();
    assertThat(action.params()).hasSize(6);
  }

  private void assertCondition(QualityGateDto qualityGate, MetricDto metric, String operator, @Nullable String warning, @Nullable String error, @Nullable Integer period) {
    assertThat(dbClient.gateConditionDao().selectForQualityGate(dbSession, qualityGate.getId()))
      .extracting(QualityGateConditionDto::getQualityGateId, QualityGateConditionDto::getMetricId, QualityGateConditionDto::getOperator,
        QualityGateConditionDto::getWarningThreshold, QualityGateConditionDto::getErrorThreshold, QualityGateConditionDto::getPeriod)
      .containsExactlyInAnyOrder(tuple(qualityGate.getId(), metric.getId().longValue(), operator, warning, error, period));
  }

  private CreateConditionResponse executeRequest(long conditionId, String metricKey, String operator, @Nullable String warning, @Nullable String error,
    @Nullable Integer period) {
    TestRequest request = ws.newRequest()
      .setParam(PARAM_ID, Long.toString(conditionId))
      .setParam(PARAM_METRIC, metricKey)
      .setParam(PARAM_OPERATOR, operator);
    if (warning != null) {
      request.setParam(PARAM_WARNING, warning);
    }
    if (error != null) {
      request.setParam(PARAM_ERROR, error);
    }
    if (period != null) {
      request.setParam(PARAM_PERIOD, Integer.toString(period));
    }
    return request.executeProtobuf(CreateConditionResponse.class);
  }

  private void logInAsQualityGateAdmin() {
    userSession.logIn().addPermission(ADMINISTER_QUALITY_GATES, db.getDefaultOrganization());
  }

  private MetricDto insertMetric() {
    return db.measures().insertMetric(m -> m.setValueType(INT.name()).setHidden(false));
  }

}
