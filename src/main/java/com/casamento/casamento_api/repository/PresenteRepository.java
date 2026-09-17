package com.casamento.casamento_api.repository;

import com.casamento.casamento_api.model.Presente;
import com.casamento.casamento_api.model.StatusPresente;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PresenteRepository extends JpaRepository<Presente, Long> {
    // Para buscar só o que ainda não foi comprado
    List<Presente> findByStatus(StatusPresente status);
}