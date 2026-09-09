package com.example.springexample.JPA_Entities;

import jakarta.persistence.*;
import lombok.*;

/**
 * Единственная оставшаяся JPA-сущность MessegerParody: нужна
 * ImageUrlPersistenceService для записи object key аватарки.
 *
 * Поле chats (@ManyToMany на user_chat) удалено вместе с сущностью Chat:
 * им никто не пользовался, а @EntityScan всё равно подхватывал Chat и
 * позволял Hibernate диктовать DDL. См. Hybrid-kubernetes-non-local-fwt.
 * Связь user_chat в этом сервисе читается реактивно через ReactiveRepository.
 */
@Getter
@Setter
@Entity
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    private String myapppassword;
    @Column(name = "image_url")
    private String imageUrl;
    @Column(name = "user_role")
    private String user_role;
    private String google_sub;
}
