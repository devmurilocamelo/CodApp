<?php

namespace App\Models\Models;

use Illuminate\Database\Eloquent\Model;
use App\Models\Usuario;


class Medico extends Model
{
    protected $fillable = [
        'nome', 
        'email', 
        'senha', 
        'cpf', 
        'telefone', 
        'crm', 
        'especialidade'
    ];

    public $timestamps = false;

    public function usuario()
    {
        return $this->belongsTo(Usuario::class, 'id', 'id');
    }
}
