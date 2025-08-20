package com.example.springexample.JPA_Repositories;

import com.example.springexample.JPA_Entities.Chat;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatRepBase  extends JpaRepository<Chat,Long> {
}
