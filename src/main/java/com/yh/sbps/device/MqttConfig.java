package com.yh.sbps.device;

import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.mqtt.core.DefaultMqttPahoClientFactory;
import org.springframework.integration.mqtt.core.MqttPahoClientFactory;
import org.springframework.integration.mqtt.inbound.MqttPahoMessageDrivenChannelAdapter;
import org.springframework.integration.mqtt.support.DefaultPahoMessageConverter;
import org.springframework.messaging.MessageChannel;

@Configuration
public class MqttConfig {

  @Value("${mqtt.host}")
  private String host;

  @Value("${mqtt.port}")
  private int port;

  @Value("${mqtt.username}")
  private String username;

  @Value("${mqtt.password}")
  private String password;

  @Bean
  public MqttPahoClientFactory mqttClientFactory() {
    DefaultMqttPahoClientFactory factory = new DefaultMqttPahoClientFactory();

    MqttConnectOptions options = new MqttConnectOptions();
    options.setServerURIs(new String[] {"ssl://" + host + ":" + port});
    options.setUserName(username);
    options.setPassword(password.toCharArray());
    options.setAutomaticReconnect(true);
    options.setCleanSession(true);

    factory.setConnectionOptions(options);
    return factory;
  }

  @Bean
  public MessageChannel mqttInputChannel() {
    return new DirectChannel();
  }

  @Bean
  public MqttPahoMessageDrivenChannelAdapter inbound(MqttPahoClientFactory mqttClientFactory) {
    MqttPahoMessageDrivenChannelAdapter adapter =
        new MqttPahoMessageDrivenChannelAdapter(
            "shellyInbound",
            mqttClientFactory,
            "shelly_1/online",
            "shelly_1/events/rpc",
            "shelly_1/status/switch:0");
    adapter.setCompletionTimeout(5000);
    adapter.setConverter(new DefaultPahoMessageConverter());
    adapter.setQos(1);
    adapter.setOutputChannel(mqttInputChannel());
    return adapter;
  }
}
