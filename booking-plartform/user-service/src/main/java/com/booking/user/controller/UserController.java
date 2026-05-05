package com.booking.user.controller;

import com.booking.user.model.User;
import com.booking.user.model.UserPreference;
import com.booking.user.repository.UserPreferenceRepository;
import com.booking.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/users")
public class UserController {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserPreferenceRepository preferenceRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @GetMapping("/{id}")
    public ResponseEntity<User> getUser(@PathVariable Long id) {
        return userRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public List<Map<String, Object>> listUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        List<User> users = userRepository.findAll(PageRequest.of(safePage, safeSize)).getContent();
        List<Map<String, Object>> result = new ArrayList<>();

        for (User user : users) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("id", user.getId());
            entry.put("name", user.getName());
            entry.put("email", user.getEmail());

            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM bookings WHERE user_id = ?",
                    Integer.class, user.getId());
            entry.put("bookingCount", count);

            result.add(entry);
        }
        return result;
    }

    @PostMapping
    public ResponseEntity<User> createUser(@RequestBody Map<String, String> data) throws Exception {
        if (data.get("name") == null || data.get("email") == null || data.get("password") == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name, email and password are required");
        }

        User user = new User();
        user.setName(data.get("name"));
        user.setEmail(data.get("email"));

        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hashBytes = md.digest(data.get("password").getBytes());
        StringBuilder sb = new StringBuilder();
        for (byte b : hashBytes) sb.append(String.format("%02x", b));
        user.setPasswordHash(sb.toString());

        return ResponseEntity.ok(userRepository.save(user));
    }

    @GetMapping("/{id}/profile")
    public Map<String, Object> getUserProfile(@PathVariable Long id) {
        String cacheKey = "user_profile_" + id;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            Map<String, Object> result = new HashMap<>();
            result.put("cached", cached);
            return result;
        }

        Map<String, Object> profile = new HashMap<>();
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        profile.put("user", user);

        List<UserPreference> prefs = preferenceRepository.findByUserId(id);
        profile.put("preferences", prefs);

        List<Map<String, Object>> bookings = jdbcTemplate.queryForList(
                "SELECT * FROM bookings WHERE user_id = ? ORDER BY created_at DESC", id);
        profile.put("bookings", bookings);

        List<Map<String, Object>> reviews = jdbcTemplate.queryForList(
                "SELECT * FROM reviews WHERE user_id = ?", id);
        profile.put("reviews", reviews);

        List<Map<String, Object>> loyalty = jdbcTemplate.queryForList(
                "SELECT * FROM loyalty_points WHERE user_id = ?", id);
        profile.put("loyalty", loyalty);

        redisTemplate.opsForValue().set(cacheKey, profile.toString(), Duration.ofMinutes(10));

        return profile;
    }

    @GetMapping("/search")
    public List<Map<String, Object>> searchUsers(@RequestParam String q) {
        String sql = "SELECT * FROM users WHERE name LIKE ? OR email LIKE ? LIMIT 100";
        String value = "%" + q + "%";
        return jdbcTemplate.queryForList(sql, value, value);
    }
}
