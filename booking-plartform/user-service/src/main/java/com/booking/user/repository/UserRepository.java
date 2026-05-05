package com.booking.user.repository;

import com.booking.user.model.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    @Query(value = "SELECT * FROM users WHERE name LIKE %:q% OR email LIKE %:q%", nativeQuery = true)
    List<User> searchByQuery(String q);
}
