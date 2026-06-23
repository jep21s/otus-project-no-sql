package com.example.orderservice.api.controller

import com.example.orderservice.mapper.DtoMapperImpl
import com.example.orderservice.service.UserService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/users")
class UserController(private val userService: UserService) {

    @GetMapping("/me")
    fun me() = DtoMapperImpl.toDto(userService.getCurrentUser())
}
