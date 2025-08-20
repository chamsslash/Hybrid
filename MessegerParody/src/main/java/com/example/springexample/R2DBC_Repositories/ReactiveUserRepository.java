package com.example.springexample.R2DBC_Repositories;

import com.example.springexample.JPA_Entities.r2dbc_user;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ReactiveUserRepository extends R2dbcRepository<r2dbc_user,Long> {
}
