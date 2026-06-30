package com.buildgraph.prototype.price;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class PriceAlertMailService {
    private final JavaMailSender mailSender;

    public PriceAlertMailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    /** Sends one target-price reached notification and reports delivery success. */
    public boolean sendTriggeredAlert(String email, String partName, int targetPrice, int currentPrice) {
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setTo(email);
            message.setSubject("[BuildGraph] 목표가 알림 도달");
            message.setText("""
                    등록한 PC 부품이 목표가 이하로 확인되었습니다.

                    부품: %s
                    목표가: %,d원
                    현재가: %,d원

                    가격은 저장된 표시 가격 기준이며 배송비, 쿠폰, 카드 할인은 제외됩니다.
                    """.formatted(partName, targetPrice, currentPrice));
            mailSender.send(message);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
