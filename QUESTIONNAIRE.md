# Questionnaire - Booking Platform

## Part A - Issues and solutions

### user-service

**1. The `listUsers()` endpoint returns a list of users together with the number of bookings each one has. As traffic grows, the endpoint becomes very slow even though each individual SQL query is fast. What is happening, and how would you fix it?**

This is an N+1 problem. The service first gets all users, and after that it runs one count query for each user. Maybe one query is fast, but many small queries together become slow. I would fix it with one query using `JOIN` and `GROUP BY`, and I would also add pagination.

**2. The `searchUsers()` endpoint accepts a string `q` and runs `WHERE name LIKE '%q%' OR email LIKE '%q%'`. What two distinct problems does this code have, and how would you fix each?**

The first problem is security, because the SQL is built with text from the user. I would use parameters to avoid SQL injection. The second problem is performance, because `LIKE '%text%'` is slow with normal indexes. I would use better indexes, full-text search, or a search service if the app becomes bigger.

**3. The `getUserProfile()` endpoint caches the assembled profile in Redis with `redisTemplate.opsForValue().set(cacheKey, profile)`. Two issues are hidden in this single line for a system at scale. What are they?**

The cache does not have TTL, so the data can stay forever. Also, there is no clear way to update or delete the cache when the user profile changes. I would add TTL and clear the key when important user data changes.

**4. `application.yml` has `spring.datasource.hikari.maximum-pool-size: 5`. Under load you observe many requests waiting and timing out at the controller layer even though the database itself is barely loaded. Why, and how would you size the pool properly?**

Only 5 connections are available from the service to the database. If more requests need DB access, they wait for a free connection. I would check Hikari metrics like active and pending connections, run a load test, and increase the pool carefully based on the database capacity.

**5. The user controller has no global exception handler. What goes wrong with that in production, and how do you fix it?**

The API can return ugly 500 errors or different error formats. This is confusing for clients. I would add a `@ControllerAdvice`, validate inputs, and return simple standard error messages.

### booking-service

**6. `createBooking()` is annotated/declared `synchronized` and uses an internal `bookingLock`. What is wrong with this design, and how should concurrency for booking creation actually be handled?**

This blocks the booking endpoint in one instance, so it reduces performance. Also, it does not solve the problem when there are multiple replicas. I would handle this closer to the database, using transactions, constraints, or database locking.

**7. `listBookings()` returns each booking enriched with the user and the room. With many bookings, the endpoint becomes very slow. What is the problem and what are two ways to fix it?**

It is another N+1 problem, but between services. For every booking, it calls user-service and inventory-service. I would fix it by adding batch endpoints, or by returning only the IDs and loading details only when they are needed.

**8. `RestTemplate` is registered as a `@Bean` with no explicit configuration. In production this leads to outages that look like the booking-service is "stuck". Explain why, and what configuration is needed.**

If there are no timeouts, a request to another service can wait for too long. Then the threads in booking-service stay blocked. I would configure connection timeout, read timeout, and probably retries and a circuit breaker.

**9. The `POST /bookings` endpoint has no idempotency mechanism. Why is this dangerous in a microservice context, and how do you implement idempotency correctly?**

This is dangerous because clients or gateways can retry the same request after a timeout. Without idempotency, the same booking can be created twice. I would use an `Idempotency-Key` and store the result for that key, so the same request returns the same response.

**10. In `createBooking()`, the call to `notification-service` happens synchronously after the booking is committed. What two failure modes does this cause, and what is the standard fix?**

If notification-service is slow, the booking request also becomes slow. If notification-service fails, the booking is already saved but the notification is not sent. A better solution is to publish an event to a queue and let notification-service process it later.

**11. The booking flow has no circuit breaker on the calls to `inventory-service` or `user-service`. Describe the failure mode this enables, and what a circuit breaker actually does.**

If one dependency is slow or down, booking-service keeps calling it and can also become slow or fail. A circuit breaker stops calls for a short time when there are many failures. This helps the system fail faster and recover better.

### inventory-service

**12. `listRooms()` is reported as "the slowest endpoint in the system" - sometimes 30+ seconds. Inspecting the code reveals two compounding problems. What are they?**

The first problem is the `Thread.sleep(500)` for every room. The second problem is that the price calculation also runs database queries for every room. With many rooms, this becomes very slow.

**13. `searchRooms()` calls its own `/rooms/{id}/availability` endpoint via HTTP for every room in the catalog. What is wrong with this approach, and what should it do instead?**

The service is calling itself by HTTP inside a loop. This adds unnecessary network calls and uses extra threads. It should use an internal method or, better, one database query to find available rooms.

**14. `computeDynamicPrice()` returns `BigDecimal.ZERO` when the room is not found. Why is this a resilience problem and not just a correctness bug? What is the right pattern?**

Returning zero hides the real error. In a real booking system this could create a wrong price. I think the service should return a clear error, like 404, or throw a controlled exception.

**15. The dynamic-pricing computation is repeated identically across many concurrent requests during a sale. Why is this a scalability problem, and what is the correct caching strategy for a value that "changes slowly"?**

Many requests repeat the same work and hit the database again and again. That does not scale well. I would cache the price for a short time, using a key with the room and the dates, because the price does not need to change every second.

### notification-service

**16. The `notify()` endpoint retries failed sends up to five times with `Thread.sleep(2000)` between attempts, all in the request thread. Describe both the local impact and the upstream impact.**

The notification-service thread is blocked for several seconds. Also, booking-service is waiting for notification-service, so booking-service can become slow too. This can affect more than one service.

**17. The retry loop uses a fixed 2-second delay. Even if it were moved off the request thread, this is still wrong. Why, and what is the correct algorithm?**

A fixed delay can make many retries happen at the same time. If the SMTP service is already failing, this can make it worse. I would use exponential backoff with jitter.

**18. `broadcast()` sends a message to every user by iterating in a `for` loop and inserting one row per recipient. Identify three distinct issues and how you would address them.**

It loads all users at once, sends emails one by one, and saves one notification at a time. I would use pagination for users, a queue or worker for emails, and batch inserts for notifications.

**19. Failed notifications are stored with `status = "failed"` and forgotten. Why is this a silent reliability bug, and how should a production system handle delivery failures?**

It is silent because nobody retries them and nobody gets alerted. In production, I would add retry jobs, metrics, alerts, and maybe a dead-letter queue for messages that keep failing.

**20. The whole notification path runs synchronously inside the HTTP request triggered by `booking-service`. What architectural change makes the system both more resilient and more scalable, and what new concerns does it introduce?**

I would use events and a message queue. Booking-service only publishes an event, and notification-service sends the email later. This is better, but now we need to care about duplicate messages, idempotency, order, and queue monitoring.

---

## Part B - Performance metrics for production diagnosis

**21. Latency percentiles (p50 / p95 / p99) per endpoint. What does it mean when p50 is healthy (e.g. 80 ms) but p99 is 5 s, and what kinds of root causes does that pattern point to?**

It means most requests are fast, but a small part of users have a very bad experience. This can happen because of locks, slow queries, GC pauses, full connection pools, or slow calls to other services.

**22. Throughput (requests per second) and saturation point. How do you experimentally determine the maximum throughput of a service, and what should you watch for as the indicator that you have crossed it?**

I would run a load test and increase the requests per second step by step. The saturation point is when throughput stops improving, but latency, errors, or queues start growing.

**23. Error rate, broken down by status class (4xx vs 5xx). Why is this distinction critical, and how do you set actionable alert thresholds?**

4xx errors usually come from bad client requests or validation problems. 5xx errors are server problems. I would alert mainly on 5xx, but also watch 4xx if they suddenly increase a lot.

**24. Database connection-pool saturation (`hikaricp_connections_pending`, `hikaricp_connections_active`). In this project, several services have `maximum-pool-size: 5`. What metric tells you the pool is the bottleneck, and what does a non-zero `pending` value mean?**

`hikaricp_connections_pending` is the important metric here. If it is above zero many times, requests are waiting for a database connection. If active connections are near 5, the pool is probably the bottleneck.

**25. Service-to-service call latency, separated from internal work. In a microservice, total request time = local work + sum of downstream call times. Why must you instrument these separately, and how does distributed tracing make this possible?**

We need to separate them because otherwise we do not know where the time is going. Distributed tracing shows spans for each service and each call, so we can see if the problem is local code or another service.

**26. JVM heap, GC pause time, and GC frequency. What pattern in these metrics indicates a memory leak, and what indicates that GC is itself becoming a performance problem?**

A memory leak can show as heap usage going up over time and not going down after GC. GC is a performance problem when pauses are long or happen too often.

**27. Thread-pool saturation (Tomcat busy threads, executor queue depth). In a Spring Boot service, what metric tells you the HTTP layer is the bottleneck, and how does it interact with downstream call latency?**

Tomcat busy threads and executor queue depth show if the HTTP layer is full. If downstream services are slow, threads stay busy longer, so the service can stop accepting requests normally.

**28. Cache hit ratio. The `getUserProfile()` endpoint caches in Redis. If the hit ratio is 30 %, what does that tell you, and what are the most common causes of a low hit ratio?**

A 30% hit ratio means most requests are not using the cache. Common causes are bad cache keys, data that is not requested again, too much invalidation, or a TTL that is too short.

**29. Apdex / SLO compliance. Instead of arguing about "is 800 ms fast enough", how do you turn user experience into a single number you can track, and what is the difference between an SLI, an SLO, and an Apdex score?**

An SLI is what we measure, for example latency. An SLO is the target we want, like 95% of requests under 500 ms. Apdex is a score from 0 to 1 that helps show if users are satisfied or frustrated.

**30. Saturation, the fourth USE/RED metric. Beyond Latency, Errors, and Throughput (RED), why is "saturation" - how full a resource is - the single best leading indicator that performance is about to degrade, and what concrete things would you measure on each service in this project?**

Saturation is useful because it warns before errors appear. If a resource is almost full, latency will probably grow soon. In this project I would measure DB pool usage, Tomcat threads, CPU, memory, GC, Redis usage, HTTP client connections, and queue size if notifications use a queue.

