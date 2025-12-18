function Math(elem)
  -- Replace \tvar{X} with \mathord{\text{'}}X
  elem.text = elem.text:gsub(
    "\\tvar%{([^}]+)%}",
    "\\mathord{\\text{'}}%1"
  )
  return elem
end
