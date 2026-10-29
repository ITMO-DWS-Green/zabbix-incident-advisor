package ru.itmo.advisor.domain.model;

/** Хост Zabbix: техническое имя ({@code host}) и видимое ({@code name}). */
public record HostRef(String host, String name) {
}
