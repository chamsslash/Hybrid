package com.example.springexample.JPA_Entities;

import jakarta.persistence.*;
import lombok.Data;
import lombok.ToString;

import java.util.ArrayList;
import java.util.List;

/**
 * Сущность живая, вопреки тому что AuthService не работает с чатами напрямую:
 * Auth_rep.findAllByChatId использует JPQL "JOIN u.chats c", а он обслуживает
 * gRPC-метод getAllUsersByChatid (Auth_impl). Удалять нельзя.
 *
 * Поле messages (@OneToMany на Message) удалено вместе с сущностью Message:
 * им никто не пользовался, но @EntityScan подхватывал Message и позволял
 * Hibernate диктовать DDL для message.time_stamp как для VARCHAR.
 * См. Hybrid-kubernetes-non-local-fwt.
 */
@Data
@ToString
@Entity
public class Chat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String title;
    private String image_url;
    @ManyToMany(mappedBy = "chats",cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    private List<User> users = new ArrayList<>();

}
