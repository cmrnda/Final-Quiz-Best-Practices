# Booking Platform - Issues

## Architecture

| Service | Port | Responsibility |
|---|---|---|
| `user-service` | 5001 | User accounts, profiles, preferences |
| `booking-service` | 5002 | Create, cancel, and list bookings |
| `inventory-service` | 5003 | Rooms, availability, and prices |
| `notification-service` | 5004 | Send confirmations, cancellations, and broadcasts |

---

## Issues by service

**P = Performance, S = Scalability, R = Resilience**

---

### 1. user-service

**File:** `user-service/src/main/java/com/booking/user/controller/UserController.java`

#### Performance

- **P1** In `listUsers()`, the service gets all users and then counts bookings one by one. This creates an N+1 problem.
- **P2** `listUsers()` does not use pagination, so with many users the response can become too big.
- **P3** `searchUsers()` uses `LIKE '%q%'`. This can be slow because normal indexes do not help much.
- **P4** `getUserProfile()` makes several database queries to build only one profile.

#### Scalability

- **S1** Using `findAll()` is not good when the table grows a lot.
- **S2** The Redis cache for the profile does not have TTL, so old data can stay there for a long time.
- **S3** The database connection pool has only 5 connections, so requests can wait under load.
- **S4** Counting bookings for every user will add more database work as the system gets more users.

#### Resilience

- **R1** There is no global exception handler, so errors can return unclear 500 responses.
- **R2** `createUser()` does not check if required fields like password are missing.
- **R3** `getUserProfile()` can return `user = null` instead of a clear 404.
- **R4** `searchUsers()` builds SQL by joining strings, so it has risk of SQL injection.

---

### 2. booking-service

**File:** `booking-service/src/main/java/com/booking/booking/controller/BookingController.java`

#### Performance

- **P1** `createBooking()` is `synchronized`, so only one booking can be created at a time in that instance.
- **P2** The method also uses `bookingLock`, so it is doing extra locking.
- **P3** `listBookings()` calls user-service and inventory-service for every booking, which is slow.
- **P4** `listBookings()` can use `findAll()` without pagination.

#### Scalability

- **S1** Java locks only work in one instance. If there are more replicas, they will not share that lock.
- **S2** Calling other services inside a loop creates many network calls.
- **S3** `RestTemplate` has no timeout configuration, so threads can stay waiting.
- **S4** `POST /bookings` does not have an idempotency key, so retries can create duplicates.

#### Resilience

- **R1** There is no circuit breaker for calls to user-service, inventory-service, or notification-service.
- **R2** The notification is sent in the same booking request, after saving the booking.
- **R3** `listBookings()` catches exceptions and ignores them, so errors are hidden.
- **R4** The availability check and the save can have race conditions if many users book at the same time.

---

### 3. inventory-service

**File:** `inventory-service/src/main/java/com/booking/inventory/controller/RoomController.java`

#### Performance

- **P1** `computeDynamicPrice()` uses `Thread.sleep(500)`, so every room has an extra delay.
- **P2** `listRooms()` calculates the dynamic price room by room.
- **P3** The price calculation makes database queries for each room.
- **P4** `searchRooms()` calls its own HTTP endpoint for each room.

#### Scalability

- **S1** `listRooms()` and `searchRooms()` load all active rooms without pagination.
- **S2** Dynamic price is calculated again on every request, instead of using cache.
- **S3** Calling the same service by HTTP uses extra threads and connections.
- **S4** `getRoom()` saves `room.toString()` in cache, without TTL or a clean JSON format.

#### Resilience

- **R1** `computeDynamicPrice()` returns zero when the room is not found, which can hide a bad error.
- **R2** If `Thread.sleep()` is interrupted, the code ignores it.
- **R3** `searchRooms()` ignores errors and just skips rooms.
- **R4** Availability queries do not have fallback or circuit breaker.

---

### 4. notification-service

**File:** `notification-service/src/main/java/com/booking/notification/controller/NotificationController.java`

#### Performance

- **P1** `notify()` retries with `Thread.sleep(2000)` inside the HTTP request.
- **P2** SMTP calls use `RestTemplate` without timeouts.
- **P3** `broadcast()` sends emails one by one.
- **P4** `listNotifications()` can return all notifications without pagination.

#### Scalability

- **S1** Notifications are processed synchronously, so many requests can block the service.
- **S2** `broadcast()` loads all users at once.
- **S3** It saves one notification at a time inside the loop.
- **S4** There is no queue or worker process for sending emails.

#### Resilience

- **R1** The retry delay is fixed, so many retries can happen together.
- **R2** Failed notifications are saved as failed but nothing else happens with them.
- **R3** `broadcast()` marks notifications as sent even if the email failed.
- **R4** If user-service fails, the notification flow can fail too.

