package com.spendings.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.spendings.fx.RateProvider.Rate;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/** Ответы НБРБ — в том виде, в каком API отдаёт их на самом деле (проверено 11.10.2026). */
class NbrbRateProviderTests {

  private static final String DAILY =
      """
      [{"Cur_ID":431,"Cur_Code":"840","Date":"2026-10-04T00:00:00","Cur_Abbreviation":"USD","Cur_Scale":1,"Cur_Name":"Доллар США","Cur_OfficialRate":3.0051},
       {"Cur_ID":456,"Cur_Code":"643","Date":"2026-10-04T00:00:00","Cur_Abbreviation":"RUB","Cur_Scale":100,"Cur_Name":"Российских рублей","Cur_OfficialRate":3.6121}]
      """;

  private MockRestServiceServer server;
  private NbrbRateProvider provider;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl("https://api.nbrb.by");
    server = MockRestServiceServer.bindTo(builder).build();
    NbrbApi api =
        HttpServiceProxyFactory.builderFor(RestClientAdapter.create(builder.build()))
            .build()
            .createClient(NbrbApi.class);
    provider = new NbrbRateProvider(api);
  }

  @Test
  void dailyRatesAreNormalizedToOneUnit() {
    server
        .expect(requestTo("https://api.nbrb.by/exrates/rates?periodicity=0&ondate=2026-10-04"))
        .andRespond(withSuccess(DAILY, MediaType.APPLICATION_JSON));

    List<Rate> rates = provider.ratesOn(LocalDate.of(2026, 10, 4));

    assertThat(rates).hasSize(2);
    assertThat(rates.get(0).perUnit()).isEqualByComparingTo("3.0051");
    // RUB котируется за 100 единиц
    assertThat(rates.get(1).currency()).isEqualTo("RUB");
    assertThat(rates.get(1).perUnit()).isEqualByComparingTo("0.036121");
    assertThat(rates.get(1).date()).isEqualTo(LocalDate.of(2026, 10, 4));
    server.verify();
  }

  @Test
  void historyUsesIdAndScaleFromTheDailyList() {
    server
        .expect(requestTo("https://api.nbrb.by/exrates/rates?periodicity=0&ondate=2026-10-04"))
        .andRespond(withSuccess(DAILY, MediaType.APPLICATION_JSON));
    server
        .expect(
            requestTo(
                "https://api.nbrb.by/exrates/rates/dynamics/456?startdate=2026-10-01&enddate=2026-10-02"))
        .andRespond(
            withSuccess(
                """
                [{"Cur_ID":456,"Date":"2026-10-01T00:00:00","Cur_OfficialRate":3.6000},
                 {"Cur_ID":456,"Date":"2026-10-02T00:00:00","Cur_OfficialRate":3.6200}]
                """,
                MediaType.APPLICATION_JSON));

    provider.ratesOn(LocalDate.of(2026, 10, 4));
    List<Rate> history =
        provider.history("RUB", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 2));

    assertThat(history)
        .extracting(Rate::perUnit)
        .usingElementComparator(java.math.BigDecimal::compareTo)
        .containsExactly(new java.math.BigDecimal("0.036"), new java.math.BigDecimal("0.0362"));
    server.verify();
  }
}
