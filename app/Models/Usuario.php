<?php

namespace App\Models;

use Illuminate\Database\Eloquent\Model;
use App\Models\Models\Medico;
use App\Models\Models\Paciente;

class Usuario extends Model
{
    protected $fillable = [
        'nome',
        'email',
        'senha',
        'tipo',
    ];

    public function paciente()
    {
        return $this->hasOne(Paciente::class, 'id', 'id');
    }

    public function medico()
    {
        return $this->hasOne(Medico::class, 'id', 'id');
    }
}