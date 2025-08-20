package com.example.springexample.JPA_Entities;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Entity
@NoArgsConstructor
public class Message {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String text;

    private String time_stamp;
    @ManyToOne
    @JoinColumn
    private User user_id;
    @ManyToOne
    @JoinColumn
    private Chat chat;

}
