package com.hoppinzq.red95.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@AllArgsConstructor
@NoArgsConstructor
@Data
@Builder
public class GameState {
    private List<Actor> actorList;
    private PlayerBaseInfo playerBaseInfo;
}
