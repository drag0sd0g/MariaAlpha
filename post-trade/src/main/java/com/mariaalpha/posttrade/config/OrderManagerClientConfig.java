package com.mariaalpha.posttrade.config;

import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class OrderManagerClientConfig {

  @Bean
  public RestClient orderManagerRestClient(TcaConfig tcaConfig) {
    HttpClient jdkClient = HttpClient.newBuilder().connectTimeout(tcaConfig.httpTimeout()).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(jdkClient);
    requestFactory.setReadTimeout(tcaConfig.httpTimeout());
    return RestClient.builder()
        .baseUrl(tcaConfig.orderManagerBaseUrl())
        .requestFactory(requestFactory)
        .build();
  }
}
