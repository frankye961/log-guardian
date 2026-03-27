package com.logguardian.service.runtime;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

final class TestCapturingMailSender extends JavaMailSenderImpl {

    private final List<MimeMessage> sentMessages = new ArrayList<>();

    @Override
    public MimeMessage createMimeMessage() {
        return new MimeMessage(Session.getInstance(new Properties()));
    }

    @Override
    public MimeMessage createMimeMessage(java.io.InputStream contentStream) {
        try {
            return new MimeMessage(Session.getInstance(new Properties()), contentStream);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Override
    public void send(MimeMessage mimeMessage) {
        sentMessages.add(copyOf(mimeMessage));
    }

    @Override
    public void send(MimeMessage... mimeMessages) {
        for (MimeMessage mimeMessage : mimeMessages) {
            send(mimeMessage);
        }
    }

    @Override
    public void send(org.springframework.mail.SimpleMailMessage simpleMessage) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void send(org.springframework.mail.SimpleMailMessage... simpleMessages) {
        throw new UnsupportedOperationException();
    }

    List<MimeMessage> sentMessages() {
        return sentMessages;
    }

    void clear() {
        sentMessages.clear();
    }

    private MimeMessage copyOf(MimeMessage original) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            original.writeTo(output);
            return new MimeMessage(
                    Session.getInstance(new Properties()),
                    new ByteArrayInputStream(output.toByteArray())
            );
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
