package com.example.springexample.JPA_Entities;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.data.relational.core.mapping.Column;
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Table("chat")
public class r2dbc_chat {
    @Id
    private Long id;

    private String title;

    @Column("image_url")
    private String imageUrl;

}
