package ru.itmo.advisor.zabbix.model;

import java.util.List;

/** Ответ host.get с selectTags и selectHostGroups. */
public record Host(String hostid, String host, String name, List<Tag> tags, List<Group> hostgroups) {

    public Host {
        tags = tags == null ? List.of() : tags;
        hostgroups = hostgroups == null ? List.of() : hostgroups;
    }

    public record Group(String groupid, String name) {
    }
}
