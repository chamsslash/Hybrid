package com.example.springexample.JPA_Entities.RowsMappers;
import com.example.springexample.JPA_Entities.r2dbc_user
;
import io.r2dbc.spi.Row;

public class UserMapper {
    public static r2dbc_user
 map(Row row) {
        r2dbc_user
 user = new r2dbc_user
();
        user.setId(row.get("id", Long.class));
        user.setName(row.get("name", String.class));
        user.setImageUrl(row.get("image_url", String.class));
        user.setUserRole(row.get("user_role", String.class));
        return user;
    }
}
