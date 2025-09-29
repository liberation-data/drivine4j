MATCH (p:Person)
  WHERE p.profession = $profession AND p.createdBy = 'test'
RETURN properties(p)