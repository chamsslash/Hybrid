package com.example.springexample.JPA_Repositories;

import com.example.springexample.JPA_Entities.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepBase extends JpaRepository<User,Long> {


    @Override
    Optional<User> findById(Long aLong);


}
