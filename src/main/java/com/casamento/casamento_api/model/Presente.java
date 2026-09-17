package com.casamento.casamento_api.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "presentes")
public class Presente {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String nome;

    private String descricao;

    @Column(columnDefinition = "TEXT")
    private String imagemUrl; // Aceita tanto Base64 (upload direto) quanto links

    @Column(nullable = false)
    private BigDecimal valor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatusPresente status = StatusPresente.DISPONIVEL;

    private String compradorNome;
    private String paymentId;

    public Presente(String nome, String descricao, String imagemUrl, BigDecimal valor) {
        this.nome = nome;
        this.descricao = descricao;
        this.imagemUrl = imagemUrl;
        this.valor = valor;
        this.status = StatusPresente.DISPONIVEL;
    }

}