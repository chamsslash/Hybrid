package com.example.springexample.Repositories;

import com.example.springexample.JPA_Entities.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface Auth_rep extends JpaRepository<User,Long> {
    @Query("SELECT u FROM User u WHERE u.name = :username")
    Optional<User> findFirstByName(@Param("username") String username);
    @Query("""
    SELECT u FROM User u
    JOIN u.chats c
    WHERE c.id = :chatId
""")
    List<User> findAllByChatId(@Param("chatId") Long chatId);
    @Query("SELECT u FROM User u WHERE u.id = :id")
    Optional<User> findFirstById(@Param("id") Long id);
    @Query("SELECT u FROM User u WHERE u.google_sub =:googlesub")
    Optional<User> findByGoogleSub(@Param("googlesub") String googleSub);
}
