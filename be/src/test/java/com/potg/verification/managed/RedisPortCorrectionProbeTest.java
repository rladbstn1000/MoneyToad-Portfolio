package com.potg.verification.managed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Pure synthetic settings/address tests: no DNS lookup or connection. */
class RedisPortCorrectionProbeTest {
    @Test void actualConfigDataAndNativeUriUseCorrectedPortWithoutPublishingValues() {
        var input = new HashMap<>(Map.of("REDIS_HOST","fixture.example.invalid","REDIS_PORT","6380",
            "REDIS_USERNAME","fixture-user","REDIS_PASSWORD","synthetic-test-only","REDIS_SSL_ENABLED","true"));
        var settings = ManagedRedisConnectivityProbe.productRedisSettings(input);
        var matches = RedisPortCorrectionProbe.settingMatches(input,settings);
        assertThat(matches).hasSize(6); assertThat(matches.values()).allMatch(Boolean::booleanValue);
        assertThat(matches.toString()).doesNotContain("fixture.example.invalid","fixture-user","synthetic-test-only","6380");
        assertThat(RedisTransportComparisonProbe.uri(settings).getPort()).isEqualTo(6380);
        assertThat(RedisTransportComparisonProbe.options(settings).getSocketOptions().getConnectTimeout().toMillis()).isEqualTo(3000);
        assertThat(RedisTransportComparisonProbe.uri(settings).getTimeout().toMillis()).isEqualTo(2000);
        input.put("REDIS_PORT","6379");
        assertThat(RedisPortCorrectionProbe.settingMatches(input,settings).get("port_matches")).isFalse();
    }
    @Test void selectsFirstIpv4FromApprovedHostnameResultsWithoutAddressOverride() throws Exception {
        String host="fixture.example.invalid";
        byte[] ipv6 = new byte[16]; ipv6[15]=1;
        var v6=InetAddress.getByAddress(host,ipv6);
        var first=InetAddress.getByAddress(host,new byte[]{127,0,0,1});
        var second=InetAddress.getByAddress(host,new byte[]{127,0,0,2});
        assertThat(RedisPortCorrectionProbe.firstIpv4(host,6380,new InetAddress[]{v6,first,second})).containsExactly(first);
        assertThatThrownBy(()->RedisPortCorrectionProbe.firstIpv4(host,6380,new InetAddress[]{v6})).hasMessage("IPV4_RESULT_REQUIRED");
        assertThatThrownBy(()->RedisPortCorrectionProbe.firstIpv4("other.invalid",6380,new InetAddress[]{first})).hasMessage("DNS_ORIGINAL_HOSTNAME_REQUIRED");
    }
}
