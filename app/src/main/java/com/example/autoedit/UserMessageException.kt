package com.veycad.app

/** Owned validation copy; callers still receive the original IllegalArgumentException contract. */
internal class UserMessageException(message: String) : IllegalArgumentException(message)
