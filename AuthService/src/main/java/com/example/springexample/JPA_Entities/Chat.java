package com.example.springexample.JPA_Entities;

import jakarta.persistence.*;
import lombok.Data;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;


@Data
@ToString
@Entity
public class Chat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String title;
    private String image_url;
    @OneToMany(mappedBy = "chat")
    private List<Message> messages = new ArrayList<>();
    @ManyToMany(mappedBy = "chats",cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    private List<User> users = new ArrayList<>();

}
