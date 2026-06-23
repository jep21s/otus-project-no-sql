package com.example.orderservice.service.exception

class NotFoundException(message: String) : RuntimeException(message)
class ForbiddenException(message: String) : RuntimeException(message)
class ConflictException(message: String) : RuntimeException(message)
