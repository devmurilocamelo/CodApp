<?php

namespace App\Models\Models;

use Illuminate\Database\Eloquent\Model;
use App\Models\Usuario;

class Paciente extends Model
{
    protected $fillable = [
        'id',
        'nome',
        'cpf',
        'dataNasc',
        'email',
        'telefone',
        'senha'
    ];
    
    public $timestamps = false; 

    public function usuario()
    {
        return $this->belongsTo(Usuario::class, 'id', 'id');
    }
}
