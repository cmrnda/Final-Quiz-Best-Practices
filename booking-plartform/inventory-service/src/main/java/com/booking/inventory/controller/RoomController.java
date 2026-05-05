package com.booking.inventory.controller;

import com.booking.inventory.model.Room;
import com.booking.inventory.repository.RoomRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;

@RestController
@RequestMapping("/rooms")
public class RoomController {

    @Autowired
    private RoomRepository roomRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private BigDecimal computeDynamicPrice(Long roomId, String checkIn, String checkOut) {
        String cacheKey = "dynamic_price_" + roomId + "_" + checkIn + "_" + checkOut;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return new BigDecimal(cached);
        }

        Room room = roomRepository.findById(roomId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));

        Integer demand = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE room_id = ? AND check_in >= ?",
                Integer.class, roomId, checkIn);

        double multiplier = 1.0 + (demand * 0.05);
        BigDecimal price = room.getBasePrice().multiply(BigDecimal.valueOf(multiplier));
        redisTemplate.opsForValue().set(cacheKey, price.toPlainString(), Duration.ofMinutes(5));
        return price;
    }

    @GetMapping
    public List<Map<String, Object>> listRooms(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        List<Room> rooms = roomRepository.findByIsActiveTrue(PageRequest.of(safePage, safeSize)).getContent();
        List<Map<String, Object>> result = new ArrayList<>();

        for (Room room : rooms) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("id", room.getId());
            entry.put("name", room.getName());
            entry.put("category", room.getCategory());
            entry.put("capacity", room.getCapacity());
            entry.put("dynamicPrice", computeDynamicPrice(room.getId(), "2024-01-01", "2024-01-02"));
            result.add(entry);
        }
        return result;
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getRoom(@PathVariable Long id) {
        String cacheKey = "room_" + id;
        String cached = redisTemplate.opsForValue().get(cacheKey);
        if (cached != null) {
            return ResponseEntity.ok(Map.of("cached", cached));
        }

        Optional<Room> room = roomRepository.findById(id);
        if (room.isEmpty()) return ResponseEntity.notFound().build();

        redisTemplate.opsForValue().set(cacheKey, room.get().toString(), Duration.ofMinutes(10));

        return ResponseEntity.ok(room.get());
    }

    @GetMapping("/{id}/availability")
    public ResponseEntity<?> checkAvailability(
            @PathVariable Long id,
            @RequestParam String checkIn,
            @RequestParam String checkOut) {

        if (!isAvailable(id, checkIn, checkOut)) {
            return ResponseEntity.status(409).body(Map.of("available", false));
        }
        return ResponseEntity.ok(Map.of("available", true));
    }

    private boolean isAvailable(Long id, String checkIn, String checkOut) {
        Integer conflicts = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE room_id = ? AND status = 'confirmed' " +
                        "AND check_in < ? AND check_out > ?",
                Integer.class, id, checkOut, checkIn);
        return conflicts == null || conflicts == 0;
    }

    @PostMapping
    public ResponseEntity<Room> createRoom(@RequestBody Room room) {
        room.setIsActive(true);
        return ResponseEntity.ok(roomRepository.save(room));
    }

    @GetMapping("/search")
    public List<Map<String, Object>> searchRooms(
            @RequestParam(required = false) String category,
            @RequestParam(defaultValue = "1") Integer minCapacity,
            @RequestParam String checkIn,
            @RequestParam String checkOut,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        List<Room> allRooms = roomRepository.findByIsActiveTrue(PageRequest.of(safePage, safeSize)).getContent();
        List<Map<String, Object>> available = new ArrayList<>();

        for (Room room : allRooms) {
            if (category != null && !room.getCategory().equals(category)) continue;
            if (room.getCapacity() < minCapacity) continue;

            if (!isAvailable(room.getId(), checkIn, checkOut)) continue;

            Map<String, Object> entry = new HashMap<>();
            entry.put("id", room.getId());
            entry.put("name", room.getName());
            entry.put("category", room.getCategory());
            entry.put("basePrice", room.getBasePrice());
            available.add(entry);
        }
        return available;
    }
}
