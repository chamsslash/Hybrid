package com.example.springexample.JPA_Entities;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.data.relational.core.mapping.Column;
@NoArgsConstructor
@AllArgsConstructor
@Getter
@Setter
@Table("users")
public class r2dbc_user {

    @Id
    private Long id;

    private String name;
    private String myapppassword;
    @Column("image_url")
    private String imageUrl;

    @Column("user_role")
    private String userRole;
    @Column("google_sub")
    private String google_sub;

}
