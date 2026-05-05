package com.booking.notification.controller;

import com.booking.notification.model.Notification;
import com.booking.notification.repository.NotificationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.concurrent.CompletableFuture;

@RestController
public class NotificationController {

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private RestTemplate restTemplate;

    @PostMapping("/notify")
    public ResponseEntity<?> notify(@RequestBody Map<String, Object> data) {
        if (!data.containsKey("userId") || !data.containsKey("type")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId and type are required");
        }

        Long userId = Long.valueOf(data.get("userId").toString());
        Long bookingId = data.get("bookingId") != null ? Long.valueOf(data.get("bookingId").toString()) : null;
        String type = (String) data.get("type");
        String message = (String) data.getOrDefault("message", "");

        Notification n = new Notification();
        n.setUserId(userId);
        n.setBookingId(bookingId);
        n.setType(type);
        n.setMessage(message);
        n.setStatus("pending");
        Notification saved = notificationRepository.save(n);

        String email;
        try {
            Map user = restTemplate.getForObject("http://user-service:5001/users/" + userId, Map.class);
            email = user != null ? (String) user.get("email") : null;
        } catch (Exception e) {
            saved.setStatus("failed");
            notificationRepository.save(saved);
            return ResponseEntity.accepted().body(Map.of("status", saved.getStatus(), "id", saved.getId()));
        }

        CompletableFuture.runAsync(() -> sendWithRetries(saved, email, type, message));

        return ResponseEntity.accepted().body(Map.of("status", saved.getStatus(), "id", saved.getId()));
    }

    private void sendWithRetries(Notification notification, String email, String type, String message) {
        boolean sent = sendEmail(email, type, message);
        notification.setStatus(sent ? "sent" : "failed");
        notification.setAttempts(1);

        if (!sent) {
            for (int i = 0; i < 5; i++) {
                try {
                    long delay = (long) (500 * Math.pow(2, i)) + new Random().nextInt(250);
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                if (sendEmail(email, type, message)) {
                    notification.setStatus("sent");
                    notification.setAttempts(notification.getAttempts() + 1);
                    break;
                }
                notification.setAttempts(notification.getAttempts() + 1);
            }
        }

        notificationRepository.save(notification);
    }

    private boolean sendEmail(String email, String type, String message) {
        try {
            ResponseEntity<String> resp = restTemplate.getForEntity(
                    "http://smtp-gateway:8080/send?to=" + email + "&type=" + type, String.class);
            return resp.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            return false;
        }
    }

    @GetMapping("/notifications")
    public List<Notification> listNotifications(
            @RequestParam(required = false) Long userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        if (userId != null) {
            return notificationRepository.findByUserId(userId, PageRequest.of(safePage, safeSize)).getContent();
        }
        return notificationRepository.findAll(PageRequest.of(safePage, safeSize)).getContent();
    }

    @GetMapping("/notifications/{id}")
    public ResponseEntity<Notification> getNotification(@PathVariable Long id) {
        return notificationRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/notify/broadcast")
    public ResponseEntity<?> broadcast(@RequestBody Map<String, Object> data) {
        String message = (String) data.get("message");
        if (message == null || message.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message is required");
        }

        int count = 0;
        int page = 0;
        int size = 50;

        while (true) {
            List users = restTemplate.getForObject(
                    "http://user-service:5001/users?page=" + page + "&size=" + size, List.class);
            if (users == null || users.isEmpty()) break;

            List<Notification> notifications = new ArrayList<>();
            for (Object u : users) {
                Map userMap = (Map) u;
                Long userId = Long.valueOf(userMap.get("id").toString());

                Notification n = new Notification();
                n.setUserId(userId);
                n.setType("broadcast");
                n.setMessage(message);

                boolean sent = sendEmail((String) userMap.get("email"), "broadcast", message);
                n.setStatus(sent ? "sent" : "failed");
                n.setAttempts(1);
                notifications.add(n);
                count++;
            }

            notificationRepository.saveAll(notifications);
            page++;
        }

        return ResponseEntity.ok(Map.of("sent", count));
    }
}
