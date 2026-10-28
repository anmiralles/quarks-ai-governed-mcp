package me.amiralles.applications.entity;

import io.quarkus.hibernate.orm.panache.PanacheEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.util.List;

@Entity
public class Application extends PanacheEntity {

    @Column
    public String name;

    @Column
    public String surname;

    @Column(length = 40)
    public String personalId;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    public ApplicationStatus status;

    @Column
    public String country;

    public Application() {
    }

    public Application(String name, String surname, String personalId, ApplicationStatus status, String country) {
        this.name = name;
        this.surname = surname;
        this.personalId = personalId;
        this.status = status;
        this.country = country;
    }

    public static Application findById(Long id){
        return find("id", id).firstResult();
    }

    public static List<Application> findByCountry(String country) {
        return find("country", country).list();
    }

    public static List<Application> findByCountryAndStatus(String country, ApplicationStatus status) {
        return find("country = ?1 and status = ?2", country, status).list();
    }

    @Override
    public String toString() {
        return "Application{" +
                "name='" + name + '\'' +
                ", surname='" + surname + '\'' +
                ", idCode='" + personalId + '\'' +
                ", status=" + status +
                ", country='" + country + '\'' +
                '}';
    }
}
